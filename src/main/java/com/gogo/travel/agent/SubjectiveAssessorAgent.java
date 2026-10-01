package com.gogo.travel.agent;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 主观评估维度 Agent。
 *
 */
public class SubjectiveAssessorAgent extends AgentBase {

    private static final Logger logger = LoggerFactory.getLogger(SubjectiveAssessorAgent.class);

    private static final Map<String, String> SOURCE_LABELS = Map.of(
            "experience", "差旅体验",
            "resilience", "行程韧性",
            "preference", "偏好匹配"
    );

    private final Model model;
    private final String systemPrompt;
    private final String source;

    /**
     * @param name         Agent 名称（用于日志/追踪）
     * @param source       评估器标识（experience / resilience / preference）
     * @param systemPrompt 系统提示词
     * @param model        LLM 模型实例
     */
    public SubjectiveAssessorAgent(String name, String source, String systemPrompt, Model model) {
        super(name, source + " 主观评估器");
        this.source = source;
        this.systemPrompt = systemPrompt;
        this.model = model;
    }

    public String getSource() {
        return source;
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        List<Msg> fullMessages = new ArrayList<>();
        fullMessages.add(Msg.builder()
                .role(MsgRole.SYSTEM)
                .name("system")
                .content(TextBlock.builder().text(systemPrompt).build())
                .build());
        if (msgs != null) {
            fullMessages.addAll(msgs);
        }

        return Mono.fromCallable(() -> {
            try {
                List<ChatResponse> responses = model.stream(fullMessages, null, null)
                        .collectList().block();
                String text = extractText(responses);
                return Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name(source)
                        .content(TextBlock.builder().text(text).build())
                        .build();
            } catch (Exception e) {
                logger.error("[{}] LLM 调用失败: {}", source, e.getMessage(), e);
                return buildFallbackMsg();
            }
        });
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        return Mono.just(buildFallbackMsg());
    }

    // ============================== 内部方法 ==============================

    private Msg buildFallbackMsg() {
        String label = SOURCE_LABELS.getOrDefault(source, source);
        String fallbackJson = String.format(
                """
                {"verdict":"warning","issues":["%s 评估器执行异常，未能完成评估"],\
                "suggestions":["建议人工复核 %s 相关维度"]}""",
                source, label);
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name(source)
                .content(TextBlock.builder().text(fallbackJson).build())
                .build();
    }

    private static String extractText(List<ChatResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatResponse response : responses) {
            List<ContentBlock> blocks = response.getContent();
            if (blocks == null) continue;
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock textBlock) {
                    sb.append(textBlock.getText());
                }
            }
        }
        return sb.toString();
    }
}
