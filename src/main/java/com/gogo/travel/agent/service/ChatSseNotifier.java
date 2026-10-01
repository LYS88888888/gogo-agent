package com.gogo.travel.agent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.tools.UserInteractionTools;
import com.gogo.travel.config.sensitive.SensitiveMasker;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * 聊天 SSE 事件推送器。
 *
 * <p>封装所有向前端 SseEmitter 发送事件的逻辑，包括：
 * <ul>
 *   <li>message —— Agent 最终回复文本</li>
 *   <li>message_id —— 该条回复持久化后的消息 ID（前端反馈用）</li>
 *   <li>error —— 执行异常信息</li>
 *   <li>interrupted —— Agent 被中断通知</li>
 *   <li>user_interaction —— Human-in-the-Loop 用户交互请求</li>
 *   <li>suggestions —— 推荐问题列表</li>
 * </ul>
 *
 */
@Component
public class ChatSseNotifier {

    private static final Logger logger = LoggerFactory.getLogger(ChatSseNotifier.class);

    /**
     * 发送 Agent 最终回复文本。
     */
    public void sendMessage(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().name("message").data(text));
        } catch (IllegalStateException e) {
            logger.debug("[SSE] 无法发送消息，emitter 已完成: {}", e.getMessage());
        } catch (IOException e) {
            logger.error("[SSE] 发送 SSE 消息失败", e);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 发送该条 AI 回复对应的持久化消息 ID。
     *
     * <p>前端流式渲染时消息 id 是本地随机生成的，无法用于点赞/点踩接口，
     * 因此在回复落库后把真实 messageId 下发，前端回填到最后一条 agent 消息上。</p>
     */
    public void sendMessageId(SseEmitter emitter, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("message_id").data(messageId));
        } catch (IllegalStateException e) {
            logger.debug("[SSE] 无法发送 message_id，emitter 已完成: {}", e.getMessage());
        } catch (IOException e) {
            logger.error("[SSE] 发送 message_id 事件失败", e);
        }
    }

    /**
     * 发送错误事件并关闭 emitter。
     */
    public void sendError(SseEmitter emitter, String errorMessage) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(SensitiveMasker.mask(errorMessage)));
            emitter.complete();
        } catch (IllegalStateException e) {
            logger.debug("[SSE] 无法发送错误消息，emitter 已完成: {}", e.getMessage());
        } catch (IOException e) {
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 发送 Agent 被中断通知。
     */
    public void sendInterrupted(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("interrupted").data("已停止生成"));
        } catch (IllegalStateException e) {
            logger.debug("[SSE] 发送 interrupted 事件时 emitter 已完成: {}", e.getMessage());
        } catch (IOException e) {
            logger.error("[SSE] 发送 interrupted 事件失败", e);
        }
    }

    /**
     * 从 TOOL_SUSPENDED 结果中提取 ask_user 工具参数，并向前端推送 user_interaction 事件。
     *
     * <p>仅对 {@code question} 文案做脱敏（LLM 常在追问中引用手机号/证件号），
     * 保留 {@code options}、{@code default_value}、{@code fields} 等结构化参数原值，
     * 避免破坏前端交互组件的可选值语义。</p>
     */
    public void sendUserInteraction(SseEmitter emitter, ToolUseBlock toolUse, String sessionId) {
//        ToolUseBlock toolUse = extractAskUserToolUse(result);
        if (toolUse == null) {
            logger.warn("[SSE] TOOL_SUSPENDED 结果中未找到 ask_user 工具调用, sessionId={}", sessionId);
            return;
        }
        try {
            JSONObject payload = JSON.parseObject(JSON.toJSONString(toolUse.getInput()));
            payload.put("toolUseId", toolUse.getId());
            // 仅对 question 文案脱敏，保留 options / default_value / fields 原值
            String question = payload.getString("question");
            if (question != null) {
                payload.put("question", SensitiveMasker.mask(question));
            }
            emitter.send(SseEmitter.event().name("user_interaction").data(payload.toJSONString()));
            logger.info("[SSE] 发送 user_interaction 事件 sessionId={}, toolUseId={}", sessionId, toolUse.getId());
        } catch (IllegalStateException e) {
            logger.debug("[SSE] 无法发送 user_interaction 事件，emitter 已完成: {}", e.getMessage());
        } catch (IOException e) {
            logger.error("[SSE] 发送 user_interaction 事件失败 sessionId={}", sessionId, e);
        }
    }

    /**
     * 发送推荐问题列表事件。
     */
    public void sendSuggestions(SseEmitter emitter, List<String> suggestions, String sessionId) {
        try {
            String payload = JSON.toJSONString(suggestions);
            emitter.send(SseEmitter.event().name("suggestions").data(payload));
            logger.info("[SSE] 发送 suggestions 事件 sessionId={}, count={}", sessionId, suggestions.size());
        } catch (IllegalStateException e) {
            logger.debug("[SSE] 无法发送 suggestions 事件，emitter 已完成: {}", e.getMessage());
        } catch (IOException e) {
            logger.error("[SSE] 发送 suggestions 事件失败 sessionId={}", sessionId, e);
        }
    }

    /**
     * 从 Msg 中提取 ask_user 工具调用块。
     */
    public ToolUseBlock extractAskUserToolUse(Msg result) {
        if (result == null) {
            return null;
        }
        return result.getContentBlocks(ToolUseBlock.class).stream()
                .filter(t -> UserInteractionTools.TOOL_NAME.equals(t.getName()))
                .findFirst()
                .orElse(null);
    }
}
