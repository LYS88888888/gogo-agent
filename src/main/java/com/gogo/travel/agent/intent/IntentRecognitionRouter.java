package com.gogo.travel.agent.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 三层意图识别编排器。
 *
 * <p>按 L1 → L2 → L3 的顺序尝试：
 * <ol>
 *   <li>L0 结构启发（前置守卫）：句中出现明显并列/顺承连词且句长达阈值时，
 *       视为疑似多意图复合句，直接跳过 L1/L2 交给 L3——L1/L2 结构上只能产出单意图，
 *       复合句短路会把多意图"吞"成单意图；</li>
 *   <li>L1 规则/关键词匹配：目标延迟 &lt; 50ms；单类命中即短路；子句级多类命中
 *       （跨目标子智能体）判为歧义，连 L2 一起跳过（L2 同样只会给单意图）；</li>
 *   <li>L2 向量相似度匹配：目标延迟 &lt; 100ms；命中即短路；</li>
 *   <li>L3 由调用方在返回 {@link Optional#empty()} 时委派给原 LLM 意图识别 Agent。</li>
 * </ol>
 *
 * <p>每次路由都会记录每层耗时，便于上线后通过日志观察命中率与延迟分布。</p>
 *
 */
@Component
public class IntentRecognitionRouter {

    private static final Logger logger = LoggerFactory.getLogger(IntentRecognitionRouter.class);

    /** L1 软目标延迟（ms），仅用于日志告警。 */
    private static final long L1_TARGET_MS = 50;
    /** L2 软目标延迟（ms），仅用于日志告警。 */
    private static final long L2_TARGET_MS = 100;

    /** 日志中字符串截断最大长度 */
    private static final int LOG_TRUNCATE_LENGTH = 80;

    /**
     * L0 结构启发使用的并列/顺承连词，复用 {@link IntentRuleMatcher#STRONG_CONJUNCTIONS}
     * （同一张表也用于 L1 的子句切分，只在一处维护）。刻意不含"和/跟/再/还要"等泛化词
     * （那些只做子句切分信号，交给 L1 守卫处理），避免把"顺便问一下差旅政策"这类
     * 单意图句也误伤到 L3 慢路径。
     */
    private static final Pattern MULTI_INTENT_CONJUNCTION =
            Pattern.compile(IntentRuleMatcher.STRONG_CONJUNCTIONS);

    /** L0 生效的最小句长：短句即使含连词也大概率是单意图（如"顺便问下餐标"）。 */
    private static final int MULTI_INTENT_MIN_LENGTH = 10;

    /** 连词至少要出现在句中（而非句首）才算多意图信号，句首连词多为口语衔接词。 */
    private static final int MULTI_INTENT_MIN_CONJUNCTION_OFFSET = 4;

    @Autowired
    private IntentRuleMatcher ruleMatcher;
    @Autowired
    private IntentVectorMatcher vectorMatcher;

    /**
     * 路由识别意图。
     *
     * @param question 改写后的问题文本（已 trim）
     * @return 命中 L1/L2 时返回结果；未命中返回 {@link Optional#empty()},由调用方兜底 L3
     */
    public Optional<IntentRecognitionResult> route(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String normalized = question.trim();

        // -------- L0：多意图结构启发 --------
        if (hasMultiIntentSignal(normalized)) {
            logger.info("[INTENT_ROUTER] L0 检测到并列/顺承连词，疑似多意图复合句，跳过 L1/L2 交给 L3 (q='{}')",
                    truncate(normalized));
            return Optional.empty();
        }

        // -------- L1 --------
        long l1Start = System.nanoTime();
        IntentRuleMatcher.Outcome l1Outcome = ruleMatcher.evaluate(normalized);
        long l1Ms = (System.nanoTime() - l1Start) / 1_000_000L;
        if (l1Outcome.verdict() == IntentRuleMatcher.Verdict.HIT) {
            logResult("L1", l1Ms, l1Outcome.result(), true);
            warnIfSlow("L1", l1Ms, L1_TARGET_MS);
            return Optional.of(l1Outcome.result());
        }
        if (l1Outcome.verdict() == IntentRuleMatcher.Verdict.AMBIGUOUS) {
            // 多类命中跨子智能体：L2 结构上同样只会产出单意图，一并跳过，交给 L3 判定 multi_intent
            logger.info("[INTENT_ROUTER] L1 子句级多类命中 {}，疑似多意图，跳过 L2 交给 L3, cost={}ms (q='{}')",
                    l1Outcome.ambiguousCategories(), l1Ms, truncate(normalized));
            return Optional.empty();
        }
        logger.debug("[INTENT_ROUTER] L1 miss, cost={}ms, fall through to L2", l1Ms);

        // -------- L2 --------
        long l2Start = System.nanoTime();
        Optional<IntentRecognitionResult> vectorHit;
        try {
            vectorHit = vectorMatcher.match(normalized);
        } catch (Exception e) {
            vectorHit = Optional.empty();
            logger.warn("[INTENT_ROUTER] L2 异常，降级到 L3: {}", e.getMessage());
        }
        long l2Ms = (System.nanoTime() - l2Start) / 1_000_000L;
        if (vectorHit.isPresent()) {
            logResult("L2", l2Ms, vectorHit.get(), true);
            warnIfSlow("L2", l2Ms, L2_TARGET_MS);
            return vectorHit;
        }
        logger.info("[INTENT_ROUTER] L1/L2 miss, will fall through to L3 (q='{}', L1={}ms, L2={}ms)",
                truncate(normalized), l1Ms, l2Ms);
        return Optional.empty();
    }

    /**
     * L0 结构启发：判断输入是否携带明显的多意图复合句信号。
     *
     * <p>判定条件（同时满足）：
     * <ol>
     *   <li>句长 ≥ {@link #MULTI_INTENT_MIN_LENGTH}；</li>
     *   <li>含 {@link #MULTI_INTENT_CONJUNCTION} 中的并列/顺承连词；</li>
     *   <li>连词出现位置 ≥ {@link #MULTI_INTENT_MIN_CONJUNCTION_OFFSET}（排除句首衔接词）。</li>
     * </ol>
     *
     * <p>包级可见，便于单元测试。误判的代价只是走 L3 慢路径，不会路由错误。</p>
     */
    static boolean hasMultiIntentSignal(String text) {
        if (text == null || text.length() < MULTI_INTENT_MIN_LENGTH) {
            return false;
        }
        Matcher matcher = MULTI_INTENT_CONJUNCTION.matcher(text);
        return matcher.find() && matcher.start() >= MULTI_INTENT_MIN_CONJUNCTION_OFFSET;
    }

    private static void logResult(String layer, long costMs,
                                  IntentRecognitionResult result, boolean hit) {
        logger.info("[INTENT_ROUTER] {} {} cost={}ms intent={} confidence={} reason={}",
                layer,
                hit ? "HIT" : "MISS",
                costMs,
                result.getPrimaryIntent(),
                result.getIntents().get(0).getConfidence().wireValue(),
                result.getOverallReason());
    }

    private static void warnIfSlow(String layer, long costMs, long targetMs) {
        if (costMs > targetMs) {
            logger.warn("[INTENT_ROUTER] {} 命中但耗时 {}ms 超过目标 {}ms，请关注 embedding/规则性能",
                    layer, costMs, targetMs);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= LOG_TRUNCATE_LENGTH ? s : s.substring(0, LOG_TRUNCATE_LENGTH) + "...";
    }
}
