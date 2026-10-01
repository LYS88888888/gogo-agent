package com.gogo.travel.agent.intent;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IntentVectorMatcher} L2 阈值与歧义 margin 测试。
 *
 * <p>通过内联 fake {@link Knowledge} 返回构造好的检索结果，验证：
 * top-1 达阈值才命中；top-1/top-2 分属不同意图且分差小于 margin 时放行（疑似多意图）；
 * 同意图或分差足够大时正常命中。</p>
 *
 */
@DisplayName("IntentVectorMatcher L2 阈值与歧义 margin 测试")
class IntentVectorMatcherTest {

    @Test
    @DisplayName("top1/top2 属不同意图且分差小于 margin 时应放行到 L3")
    void shouldAbstainWhenTopTwoIntentsAreClose() {
        IntentVectorMatcher matcher = matcherOf(
                doc(IntentCategory.FLIGHT_SEARCH, "帮我看看去北京的航班", 0.82),
                doc(IntentCategory.HOTEL_SEARCH, "帮我找个住的地方", 0.79));

        Optional<IntentRecognitionResult> result = matcher.match("帮我订去北京的机票再找个住的地方");
        assertTrue(result.isEmpty(), "top1-top2 分差 0.03 < margin 0.05 且意图不同，应放行到 L3");
    }

    @Test
    @DisplayName("top1/top2 分差大于 margin 时应正常命中 top1 意图")
    void shouldHitWhenMarginIsLargeEnough() {
        IntentVectorMatcher matcher = matcherOf(
                doc(IntentCategory.FLIGHT_SEARCH, "帮我看看去北京的航班", 0.90),
                doc(IntentCategory.HOTEL_SEARCH, "帮我找个住的地方", 0.76));

        Optional<IntentRecognitionResult> result = matcher.match("看看明天去北京的航班");
        assertTrue(result.isPresent(), "分差 0.14 >= margin，应命中");
        assertEquals(IntentCategory.FLIGHT_SEARCH, result.get().getPrimary());
        assertEquals(IntentRecognitionResult.Confidence.HIGH, result.get().getIntents().get(0).getConfidence());
    }

    @Test
    @DisplayName("top1/top2 同属一个意图时分差小也应命中（无歧义）")
    void shouldHitWhenTopTwoShareSameIntent() {
        IntentVectorMatcher matcher = matcherOf(
                doc(IntentCategory.POLICY_QUERY, "出差住宿有什么要求", 0.80),
                doc(IntentCategory.POLICY_QUERY, "住宿费用有上限吗", 0.79));

        Optional<IntentRecognitionResult> result = matcher.match("出差住酒店有什么讲究");
        assertTrue(result.isPresent(), "top1/top2 同意图不构成歧义，应命中");
        assertEquals(IntentCategory.POLICY_QUERY, result.get().getPrimary());
    }

    @Test
    @DisplayName("top1 低于阈值时应放行到 L3")
    void shouldAbstainWhenBelowThreshold() {
        IntentVectorMatcher matcher = matcherOf(
                doc(IntentCategory.GENERAL_INFO, "那边冷不冷", 0.60));

        assertTrue(matcher.match("随便聊聊").isEmpty(), "top1 0.60 < 阈值 0.75，应放行到 L3");
    }

    @Test
    @DisplayName("只有一条检索结果时按阈值正常命中")
    void shouldHitWithSingleDocument() {
        IntentVectorMatcher matcher = matcherOf(
                doc(IntentCategory.REIMBURSEMENT, "这张票据帮我处理下", 0.88));

        Optional<IntentRecognitionResult> result = matcher.match("这张票据帮我弄一下");
        assertTrue(result.isPresent());
        assertEquals(IntentCategory.REIMBURSEMENT, result.get().getPrimary());
    }

    // ----------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------

    /**
     * 构造固定返回给定检索结果的 fake Knowledge，并包装为待测 matcher。
     */
    private static IntentVectorMatcher matcherOf(Document... docs) {
        Knowledge fake = new Knowledge() {
            @Override
            public Mono<Void> addDocuments(List<Document> documents) {
                return Mono.empty();
            }

            @Override
            public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
                return Mono.just(List.of(docs));
            }
        };
        return new IntentVectorMatcher(fake);
    }

    /**
     * 构造带意图 payload 与相似度得分的检索结果文档（与
     * {@code IntentRouterKnowledgeConfig#buildSeedDocuments} 的 payload 结构一致）。
     */
    private static Document doc(IntentCategory category, String example, double score) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("intent", category.getCode());
        payload.put("target_agent", category.getDefaultTargetAgent());

        DocumentMetadata metadata = DocumentMetadata.builder()
                .content(TextBlock.builder().text(example).build())
                .docId("intent-" + category.getCode())
                .chunkId("0")
                .payload(payload)
                .build();
        Document document = new Document(metadata);
        document.setScore(score);
        return document;
    }
}
