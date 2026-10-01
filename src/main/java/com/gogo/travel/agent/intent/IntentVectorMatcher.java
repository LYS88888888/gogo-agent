package com.gogo.travel.agent.intent;

import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * L2 向量相似度匹配器。
 *
 * <p>使用 {@code intentRouterKnowledge} 知识库（见
 * {@link com.gogo.travel.config.IntentRouterKnowledgeConfig}）执行 Top-2 检索：
 * top-1 相似度 &gt;= {@link #DEFAULT_SCORE_THRESHOLD} 时为命中候选，再用 top-2 做歧义校验——
 * 若 top-1/top-2 分属不同意图且分差 &lt; {@link #DEFAULT_SCORE_MARGIN}，视为多意图/意图摇摆，
 * 放行到 L3。embedding 计算与检索都走
 * {@link io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding} 远程接口，
 * 因此 L2 仍然要走一次网络，但与 L3 完整 ReAct 推理相比成本和延迟都低一个量级。</p>
 *
 * <p>阈值默认 0.75，偏保守以减少误判；当 {@code app.intent-recognition.vector-threshold} 配置后
 * 可调。命中后 LLM 兜底不再被调用，目标延迟 &lt; 100ms。</p>
 *
 * <p>本类 <b>不</b>使用 {@code @Component}，因为容器中存在多个 {@link Knowledge} bean
 * （RAG 景点/政策库 + 本意图路由库），构造函数参数自动注入会因类型不唯一而失败。
 * bean 注册在 {@link com.gogo.travel.config.IntentRouterKnowledgeConfig} 中完成。</p>
 *
 */
public class IntentVectorMatcher {

    private static final Logger logger = LoggerFactory.getLogger(IntentVectorMatcher.class);

    /** 默认相似度阈值；可通过 application.yml 调整。 */
    public static final double DEFAULT_SCORE_THRESHOLD = 0.75;

    /**
     * 默认歧义 margin：top-1 与 top-2 属于不同意图且分差小于该值时，认为输入在两个意图之间
     * 摇摆（复合句的 embedding 常同时贴近多个单意图种子样本），保守放行到 L3。
     */
    public static final double DEFAULT_SCORE_MARGIN = 0.05;

    /** Top-K 检索上限；top-1 用于命中判定，top-2 用于意图歧义（margin）校验。 */
    private static final int TOP_K = 2;

    private final Knowledge knowledge;

    private double scoreThreshold;

    private double scoreMargin;

    public IntentVectorMatcher(Knowledge knowledge) {
        this(knowledge, DEFAULT_SCORE_THRESHOLD);
    }

    public IntentVectorMatcher(Knowledge knowledge, double scoreThreshold) {
        this.knowledge = knowledge;
        this.scoreThreshold = scoreThreshold;
        this.scoreMargin = DEFAULT_SCORE_MARGIN;
    }

    /**
     * 设置相似度阈值，便于外部动态调整（未接入配置中心，保留扩展点）。
     */
    public void setScoreThreshold(double scoreThreshold) {
        if (scoreThreshold < 0.0 || scoreThreshold > 1.0) {
            throw new IllegalArgumentException("scoreThreshold must be in [0, 1]");
        }
        this.scoreThreshold = scoreThreshold;
    }

    public double getScoreThreshold() {
        return scoreThreshold;
    }


    public double getScoreMargin() {
        return scoreMargin;
    }

    /**
     * 对输入文本执行 L2 向量检索。
     *
     * @param text 改写后的问题文本（已 trim）
     * @return 命中时返回结果，未命中返回 {@link Optional#empty()}
     */
    public Optional<IntentRecognitionResult> match(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = text.trim();

        RetrieveConfig config = RetrieveConfig.builder()
                .limit(TOP_K)
                // 先取 top2，由我们自己做阈值/margin 过滤，方便拿到原始 score
                .scoreThreshold(0.0)
                .build();

        List<Document> docs;
        try {
            docs = knowledge.retrieve(normalized, config).block();
        } catch (Exception e) {
            // 任何 embedding/检索失败都视为未命中，让 L3 兜底；不阻塞主流程
            logger.warn("[INTENT_ROUTER] L2 向量检索失败，降级到 L3: {}", e.getMessage());
            return Optional.empty();
        }

        if (docs == null || docs.isEmpty()) {
            return Optional.empty();
        }

        Document top = docs.get(0);
        Double score = top.getScore();
        if (score == null || score < scoreThreshold) {
            logger.debug("[INTENT_ROUTER] L2 top-1 相似度 {} < 阈值 {}，放行到 L3", score, scoreThreshold);
            return Optional.empty();
        }

        Object intentCode = top.getPayloadValue("intent");
        if (intentCode == null) {
            return Optional.empty();
        }

        // margin 校验：top-1/top-2 分属不同意图且分差过小时，说明输入在两个意图间摇摆
        // （多意图复合句的典型表现），保守放行到 L3，避免"吞"掉另一半意图。
        if (docs.size() >= 2) {
            Document second = docs.get(1);
            Double secondScore = second.getScore();
            Object secondIntent = second.getPayloadValue("intent");
            if (secondScore != null && secondIntent != null
                    && !intentCode.toString().equals(secondIntent.toString())
                    && score - secondScore < scoreMargin) {
                logger.info("[INTENT_ROUTER] L2 意图歧义：top1={}({}) 与 top2={}({}) 分差 {} < margin {}，放行到 L3",
                        intentCode, String.format("%.3f", score),
                        secondIntent, String.format("%.3f", secondScore),
                        String.format("%.3f", score - secondScore), scoreMargin);
                return Optional.empty();
            }
        }

        IntentCategory category = IntentCategory.fromCode(intentCode.toString());

        ConfidenceFromScore conf = classify(score);
        return Optional.of(IntentRecognitionResult.single(
                IntentRecognitionResult.Source.VECTOR,
                category,
                conf.confidence,
                "L2 向量命中：相似度=" + String.format("%.3f", score)
                        + "，匹配样本「" + top.getMetadata().getContentText() + "」",
                score));
    }

    private static ConfidenceFromScore classify(double score) {
        if (score >= 0.85) {
            return new ConfidenceFromScore(IntentRecognitionResult.Confidence.HIGH);
        }
        if (score >= 0.75) {
            return new ConfidenceFromScore(IntentRecognitionResult.Confidence.MEDIUM);
        }
        return new ConfidenceFromScore(IntentRecognitionResult.Confidence.LOW);
    }

    private record ConfidenceFromScore(IntentRecognitionResult.Confidence confidence) {}
}
