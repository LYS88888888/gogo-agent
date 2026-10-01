package com.gogo.travel.agent.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IntentRecognitionRouter} L0 多意图结构启发测试。
 *
 * <p>L0 只做纯文本判定（连词 + 句长 + 连词位置），不依赖 Spring 容器，
 * 通过包级静态方法 {@code hasMultiIntentSignal} 直接验证。
 * 判定为 true 的代价只是放弃 L1/L2 快路径走 L3，不会导致路由错误，
 * 因此用例侧重"该拦的拦住、明显单意图的别误伤"。</p>
 *
 */
@DisplayName("IntentRecognitionRouter L0 多意图结构启发测试")
class IntentRecognitionRouterTest {

    @Test
    @DisplayName("句中含并列/顺承连词的长复合句应被识别为多意图信号")
    void shouldDetectMultiIntentSignal() {
        List<String> positives = List.of(
                "帮我订下周去北京的机票，然后再订个酒店",
                "查下北京的天气，顺便看看有什么好玩的",
                "帮我提个出差申请，另外查一下报销进度",
                "先帮我查高铁票，接着把酒店也定了",
                "订完机票之后再帮我约个会议室");

        List<String> failures = new ArrayList<>();
        for (String text : positives) {
            if (!IntentRecognitionRouter.hasMultiIntentSignal(text)) {
                failures.add(String.format("「%s」期望=命中多意图信号，实际=未命中", text));
            }
        }
        assertTrue(failures.isEmpty(), buildReport("漏判(应拦截却放行)", failures));
    }

    @Test
    @DisplayName("单意图表达不应被误判为多意图信号")
    void shouldNotFalsePositiveOnSingleIntent() {
        List<String> negatives = new ArrayList<>(List.of(
                // 连词在句首：口语衔接词，不是多意图信号
                "顺便问一下差旅政策是什么",
                "然后呢",
                // 短句：即使含连词也大概率单意图
                "顺便查下餐标",
                // 无连词的普通单意图长句
                "帮我查一下下周去上海的航班信息",
                "我想了解一下公司的差旅报销政策"));
        negatives.add(null);

        List<String> failures = new ArrayList<>();
        for (String text : negatives) {
            if (IntentRecognitionRouter.hasMultiIntentSignal(text)) {
                failures.add(String.format("「%s」期望=放行，实际=误判为多意图信号", text));
            }
        }
        assertTrue(failures.isEmpty(), buildReport("误伤(单意图被拦截)", failures));
    }

    private static String buildReport(String title, List<String> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("，共 ").append(failures.size()).append(" 条失败：\n");
        for (String f : failures) {
            sb.append("  - ").append(f).append('\n');
        }
        return sb.toString();
    }
}
