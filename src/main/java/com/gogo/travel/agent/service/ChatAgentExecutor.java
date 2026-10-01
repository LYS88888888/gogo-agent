package com.gogo.travel.agent.service;

import com.alibaba.fastjson2.JSON;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.context.SessionReactorContext;
import com.gogo.travel.agent.registry.AgentRegistry;
import com.gogo.travel.agent.registry.AgentExecutionRegistry;
import com.gogo.travel.agent.registry.SseEmitterRegistry;
import com.gogo.travel.agent.session.AgentInterruptBroadcast;
import com.gogo.travel.agent.session.AgentSession;
import com.gogo.travel.agent.session.AgentSessionManager;
import com.gogo.travel.agent.session.PendingToolSessionStore;
import com.gogo.travel.agent.session.record.PendingToolState;
import com.gogo.travel.agent.tools.UserInteractionTools;
import com.gogo.travel.business.chat.service.ChatHistoryService;
import com.gogo.travel.config.sensitive.SensitiveMasker;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.gogo.travel.agent.service.AgentPipelineService.PIPELINE_AGENT_NAMES;

/**
 * 聊天 Agent 执行器。
 *
 * <p>封装 Agent 的响应式订阅生命周期管理，包括：
 * <ul>
 *   <li>执行 Agent 并订阅结果流（含 Pipeline 与直接续跑两种模式）</li>
 *   <li>处理 Agent 执行结果（正常回复 / TOOL_SUSPENDED / 错误）</li>
 *   <li>中断正在执行的 Agent（本地中断 + 集群广播）</li>
 *   <li>恢复 ask_user 暂停的 Agent 执行（本地命中 / 跨节点重建）</li>
 *   <li>推荐问题生成与推送</li>
 * </ul>
 *
 * <p>从 {@code ChatController} 中提取此类的目的是实现 Controller 瘦身：
 * Controller 仅负责 HTTP 协议解析与参数校验，执行编排逻辑集中在此。</p>
 *
 */
@Service
public class ChatAgentExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ChatAgentExecutor.class);

    /** SSE 连接超时时间（毫秒），30 分钟，覆盖最长规划任务 */
    private static final long SSE_EMITTER_TIMEOUT_MILLIS = 1_800_000L;

    @Autowired
    private AgentRegistry agentRegistry;
    @Autowired
    private AgentPipelineService agentPipelineService;
    @Autowired
    private SseEmitterRegistry emitterRegistry;
    @Autowired
    private AgentSessionManager agentSessionManager;
    @Autowired
    private AgentExecutionRegistry executionRegistry;
    @Autowired
    private AgentInterruptBroadcast interruptBroadcast;
    @Autowired
    private QuestionRecommendationService questionRecommendationService;
    @Autowired
    private PendingToolSessionStore pendingToolSessionStore;
    @Autowired
    private ChatHistoryService chatHistoryService;
    @Autowired
    private ChatSseNotifier sseNotifier;

    /**
     * 创建新的 SseEmitter 实例（统一超时配置）。
     */
    public SseEmitter createEmitter() {
        return new SseEmitter(SSE_EMITTER_TIMEOUT_MILLIS);
    }

    /**
     * 中断指定 session 正在执行的 Agent。
     *
     * <p>优先本地中断，本地未命中时通过 Redis Pub/Sub 广播到其他节点。
     * 同时清理 pending tool 状态与本地 Agent 缓存。</p>
     *
     * @return true 表示中断指令已发出
     */
    public boolean interrupt(String sessionId) {
        boolean interrupted = interruptBroadcast.interruptLocalSession(sessionId);
        if (!interrupted) {
            interruptBroadcast.broadcastInterrupt(sessionId);
            interrupted = true;
        }
        return interrupted;
    }

    /**
     * 新请求到达前的预中断：优雅停止本节点可能残留的同一 session 执行流，
     * 并通过 Redis Pub/Sub 广播到其他节点。
     */
    public void interruptPrevious(String sessionId) {
        executionRegistry.interruptLocal(sessionId);
        interruptBroadcast.broadcastInterrupt(sessionId);
    }

    /**
     * 执行 Agent 并将结果通过 SSE 流推送给前端。
     *
     * @param agent         目标 Agent（null 表示走完整 Pipeline 由 MasterAgent 协调），
     *                      可以是 {@link ReActAgent}，也可以是意图识别 / 问题改写这类流水线 Agent
     * @param inputMessages 用户输入消息列表
     * @param latestUserText 用户最新输入文本（用于推荐问题生成）
     * @param emitter       SSE 发射器
     * @param sessionCtx    本次执行的会话上下文（与 Agent 工具持有的同一实例）
     */
    public void executeAgent(Agent agent, List<Msg> inputMessages, String latestUserText,
                             SseEmitter emitter, AgentSessionContext sessionCtx) {
        String sessionId = sessionCtx.getSessionId();
        String userId = sessionCtx.getUserId();
        emitterRegistry.register(sessionId, emitter);
        long start = System.currentTimeMillis();
        AtomicBoolean suspended = new AtomicBoolean(false);
        AtomicReference<Msg> lastResult = new AtomicReference<>();
        AtomicReference<String> lastMaskedText = new AtomicReference<>();

        Mono<Msg> execution;
        if (agent == null) {
            execution = agentPipelineService.executeFullPipeline(inputMessages, sessionCtx);
        } else {
            logger.info("[EXECUTOR] 会话 {} 命中 continuation 信号, 继续使用 Agent: {}", sessionId, agent.getName());
            if (PIPELINE_AGENT_NAMES.contains(agent.getName())) {
                execution = agentPipelineService.executePipeline(inputMessages, sessionCtx, agent.getName());
            } else {
                executionRegistry.register(sessionId, agent);
                execution = agent.call(inputMessages)
                        .doFinally(signal -> executionRegistry.remove(sessionId))
                        .contextWrite(SessionReactorContext.of(sessionCtx));
            }
        }

        execution.subscribe(
                        result -> {
                            long cost = System.currentTimeMillis() - start;
                            GenerateReason reason = result.getGenerateReason();
                            logger.info("[EXECUTOR] Agent 执行完成, 耗时{}ms, reason={}, 返回长度={}",
                                    cost, reason, result.getTextContent().length());
                            lastResult.set(result);
                            suspended.set(handleAgentResult(agent, result, emitter, sessionId, userId, lastMaskedText));
                        },
                        error -> {
                            long cost = System.currentTimeMillis() - start;
                            logger.error("[EXECUTOR] Agent 执行失败, 耗时{}ms: {}",
                                    cost, error.getMessage(), error);
                            sseNotifier.sendError(emitter, error.getMessage());
                        },
                        () -> {
                            logger.info("[EXECUTOR] SSE 流主流程完成 sessionId={}", sessionId);
                            if (!suspended.get() && lastResult.get() != null) {
                                sendSuggestionsThenComplete(latestUserText, lastResult.get(),
                                        lastMaskedText.get(), emitter, sessionId);
                            } else {
                                emitter.complete();
                            }
                        });
    }

    /**
     * 恢复 ask_user 暂停的 Agent 执行（用户回复 Tool Suspend）。
     *
     * <p>集群支持：优先从本节点 {@link AgentSessionManager} 取已有实例；
     * 若本地未命中，则从共享 {@link PendingToolSessionStore} 读取 agentName 并重建实例。</p>
     *
     * @param sessionCtx  本次执行的会话上下文（与 Agent 工具持有的同一实例）
     * @param toolUseId   对应 ToolUseBlock 的 id
     * @param response    用户回复内容
     * @return SSE 发射器
     */
    public SseEmitter resume(AgentSessionContext sessionCtx, String toolUseId, Object response) {
        String sessionId = sessionCtx.getSessionId();
        String userId = sessionCtx.getUserId();

        // --- 第零步：安全预中断 ---
        // 防止因前端竞态导致同一 session 仍有 Agent 在运行，避免并发执行。
        // 仅做本地中断，不广播：广播会清理 pendingToolSessionStore，而 resume 依赖它来恢复状态。
        executionRegistry.interruptLocal(sessionId);

        // --- 第一步：解析 Agent 实例 ---
        AgentSession localSession = agentSessionManager.getBySessionId(sessionId);
        ReActAgent agent;
        boolean recoveredFromSession;

        if (localSession != null && localSession.getAgent() != null
                && toolUseId.equals(localSession.getPendingToolUseId())) {
            agent = localSession.getAgent();
            recoveredFromSession = false;
            logger.debug("[EXECUTOR] respond 命中本地 AgentSession, sessionId={}, toolUseId={}", sessionId, toolUseId);
        } else {
            if (localSession != null && localSession.getAgent() != null) {
                logger.info("[EXECUTOR] 本地 AgentSession 已过期(pendingToolUseId 不匹配: local={}, request={}), "
                        + "将从共享存储恢复, sessionId={}", localSession.getPendingToolUseId(), toolUseId, sessionId);
                agentSessionManager.remove(sessionId);
            }
            PendingToolState pending = pendingToolSessionStore.get(sessionId);
            if (pending == null) {
                SseEmitter emitter = new SseEmitter(0L);
                sseNotifier.sendError(emitter, "会话已过期或不存在，请重新开始对话");
                return emitter;
            }
            agent = agentRegistry.getAgent(pending.agentName(), ReActAgent.class);
            if (agent == null) {
                SseEmitter emitter = new SseEmitter(0L);
                sseNotifier.sendError(emitter, "无法恢复会话：未知的 Agent 类型 " + pending.agentName());
                return emitter;
            }
            recoveredFromSession = true;
            logger.info("[EXECUTOR] respond 跨节点恢复, sessionId={}, agent={}, toolUseId={}",
                    sessionId, pending.agentName(), pending.toolUseId());
        }

        // --- 第二步：持久化用户回复 ---
        try {
            String userResponseText = response == null ? "" : (response instanceof String s ? s : JSON.toJSONString(response));
            chatHistoryService.saveUserMessage(sessionId, userId, userResponseText);
        } catch (Exception e) {
            logger.warn("[EXECUTOR] 保存用户回复失败 sessionId={}: {}", sessionId, e.getMessage());
        }

        // --- 第三步：构建 ToolResultBlock ---
        String responseText;
        if (response == null) {
            responseText = "";
        } else if (response instanceof String s) {
            responseText = s;
        } else {
            responseText = JSON.toJSONString(response);
        }
        ToolResultBlock resultBlock = new ToolResultBlock(
                toolUseId,
                UserInteractionTools.TOOL_NAME,
                List.of(TextBlock.builder().text(responseText).build()));
        Msg toolResultMsg = Msg.builder()
                .role(MsgRole.TOOL)
                .name("user")
                .content(resultBlock)
                .build();

        // --- 第四步：续跑 Agent ---
        SseEmitter emitter = new SseEmitter(SSE_EMITTER_TIMEOUT_MILLIS);
        emitterRegistry.register(sessionId, emitter);
        executionRegistry.register(sessionId, agent);
        AtomicBoolean suspended = new AtomicBoolean(false);
        AtomicReference<Msg> lastResult = new AtomicReference<>();
        AtomicReference<String> lastMaskedText = new AtomicReference<>();
        final ReActAgent finalAgent = agent;
        final String finalResponseText = responseText;

        agent.call(List.of(toolResultMsg))
                // 跨节点重建的实例内存为空，需要加载历史；本地命中的则要跳过，否则覆盖 pending tool 状态
                .contextWrite(SessionReactorContext.of(sessionCtx, !recoveredFromSession))
                .doFinally(signal -> executionRegistry.remove(sessionId))
                .subscribe(
                        result -> {
                            lastResult.set(result);
                            suspended.set(handleAgentResult(finalAgent, result, emitter, sessionId, userId, lastMaskedText));
                        },
                        error -> {
                            logger.error("[EXECUTOR] 恢复 Agent 执行失败 sessionId={}: {}",
                                    sessionId, error.getMessage(), error);
                            agentSessionManager.remove(sessionId);
                            pendingToolSessionStore.clear(sessionId);
                            sseNotifier.sendError(emitter, error.getMessage());
                        },
                        () -> {
                            logger.info("[EXECUTOR] 恢复执行 SSE 流完成 sessionId={}", sessionId);
                            if (!suspended.get()) {
                                agentSessionManager.remove(sessionId);
                                pendingToolSessionStore.clear(sessionId);
                            }
                            if (!suspended.get() && isMasterAgent(finalAgent) && lastResult.get() != null) {
                                sendSuggestionsThenComplete(finalResponseText, lastResult.get(),
                                        lastMaskedText.get(), emitter, sessionId);
                            } else {
                                emitter.complete();
                            }
                        });
        return emitter;
    }

    // ===================== 内部方法 =====================

    /**
     * 处理 Agent 执行结果：如果是 TOOL_SUSPENDED，则缓存 Agent 实例并向前端推送 user_interaction 事件；
     * 否则直接推送最终文本回复。
     *
     * @param maskedTextRef 输出参数：非 TOOL_SUSPENDED 时存放脱敏后的文本，供推荐问生成使用
     * @return true 表示当前结果触发了暂停，需要等待用户回复
     */
    private boolean handleAgentResult(Agent agent, Msg result, SseEmitter emitter,
                                      String sessionId, String userId,
                                      AtomicReference<String> maskedTextRef) {
        if (result.getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
            ToolUseBlock toolUse = sseNotifier.extractAskUserToolUse(result);
            if (toolUse != null) {
                String agentName = agent != null ? agent.getName() : "MasterAgent";
                logger.info("[EXECUTOR] Agent 执行结果触发工具调用暂停, sessionId={}, agent={}, toolUseId={}",
                        sessionId, agentName, toolUse.getId());
                // 本地缓存（同节点快速恢复）；仅 ReActAgent 支持工具暂停后的续跑
                ReActAgent resumableAgent = agent instanceof ReActAgent reActAgent ? reActAgent : null;
                agentSessionManager.register(new AgentSession(sessionId, resumableAgent, toolUse.getId()));
                // 持久化到共享 Session（集群任意节点恢复）
                pendingToolSessionStore.save(sessionId, agentName, toolUse.getId(),
                        UserInteractionTools.TOOL_NAME);
                sseNotifier.sendUserInteraction(emitter, toolUse, sessionId);
                return true;
            }
        }
        // 返回给前端的最终答案做敏感信息脱敏
        String text = SensitiveMasker.mask(result.getTextContent());
        maskedTextRef.set(text);
        String agentName = agent != null ? agent.getName() : "MasterAgent";
        String messageId = null;
        try {
            messageId = chatHistoryService.saveAssistantMessage(sessionId, userId, text, agentName, null);
        } catch (Exception e) {
            logger.warn("[EXECUTOR] 保存AI回复失败 sessionId={}: {}", sessionId, e.getMessage());
        }
        sseNotifier.sendMessage(emitter, text);
        // 先发正文再发 messageId，前端拿到后回填到刚渲染出的这条消息上
        sseNotifier.sendMessageId(emitter, messageId);
        return false;
    }

    /**
     * 在 Agent 返回最终答案后，调用问题推荐智能体生成推荐问题，
     * 通过 SSE 推送给前端，然后关闭流。
     */
    private void sendSuggestionsThenComplete(String latestUserText, Msg finalResult,
                                             String maskedAssistantText,
                                             SseEmitter emitter, String sessionId) {
        if (finalResult.getGenerateReason() == GenerateReason.TOOL_SUSPENDED
                || finalResult.getGenerateReason() == GenerateReason.INTERRUPTED) {
            emitter.complete();
            return;
        }
        questionRecommendationService.recommend(latestUserText, maskedAssistantText)
                .subscribe(
                        suggestions -> {
                            if (!suggestions.isEmpty()) {
                                sseNotifier.sendSuggestions(emitter, suggestions, sessionId);
                            }
                        },
                        error -> logger.warn("[EXECUTOR] 推荐问题生成失败 sessionId={}: {}", sessionId, error.getMessage()),
                        emitter::complete);
    }

    private boolean isMasterAgent(Agent agent) {
        if (agent == null) {
            return true;
        }
        return "MasterAgent".equals(agent.getName());
    }
}
