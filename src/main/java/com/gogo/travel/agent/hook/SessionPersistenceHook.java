package com.gogo.travel.agent.hook;

import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.context.SessionReactorContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 利用 AgentScope 框架原生 Session/Memory 机制实现多轮对话记忆持久化的 Hook。
 *
 */
public class SessionPersistenceHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(SessionPersistenceHook.class);

    private final Session session;

    /**
     * 暂存无状态 AgentBase 在 PreCall 阶段拼接好的「历史 + 本轮输入」消息列表，供 PostCall 阶段
     * 追加本轮回复后一并写回 Session；PostCall 消费后立即移除。Key 为 sessionKey（sessionId:agentName）。
     *
     * <p>注意：若调用被中断（InterruptedException），PostCall 不会触发，对应条目会在下一次
     * 该 sessionKey 成功完成的 PreCall 时被覆盖，不会无限增长。
     */
    private final Map<String, List<Msg>> pendingStatelessInput = new ConcurrentHashMap<>();

    public SessionPersistenceHook(Session session) {
        this.session = session;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreCallEvent) && !(event instanceof PostCallEvent)) {
            return Mono.just(event);
        }

        return Mono.deferContextual(ctx -> {

            AgentSessionContext sessionCtx = SessionReactorContext.from(ctx);
            if (sessionCtx == null) {
                logger.debug("[SessionPersistence]  未从 ReactorContext 中提取到会话上下文，跳过对话记忆持久化");
                return Mono.just(event);
            }
            String sessionId = sessionCtx.getSessionId();

            String agentName = event.getAgent() != null ? event.getAgent().getName() : null;
            if (agentName == null) {
                return Mono.just(event);
            }

            // Session Key = "sessionId:agentName"，保证不同会话、不同 Agent 状态隔离
            String sessionKey = sessionId + ":" + agentName;
            // 恢复被 ToolSuspendException 暂停的 Agent 时，必须保留内存中已有的 pending tool 状态，
            // 因此跳过从 Session 加载，避免覆盖当前执行上下文。
            boolean resuming = SessionReactorContext.isResuming(ctx);

            persistReActAgent(event, (ReActAgent) event.getAgent(), sessionKey, sessionId, agentName, resuming);

            return Mono.just(event);
        });
    }

    /**
     * ReActAgent（持有 {@link Memory}）的 Session 持久化：沿用 StateModule 原生 saveTo/loadIfExists。
     */
    private void persistReActAgent(HookEvent event, ReActAgent reActAgent, String sessionKey,
                                   String sessionId, String agentName, boolean resuming) {
        if (event instanceof PreCallEvent) {
            if (resuming) {
                logger.debug("[SessionPersistence] 恢复暂停 Agent，跳过历史记忆加载: sessionId={}, agent={}", sessionId, agentName);
            } else {
                // 加载上一轮历史记忆（首次请求时 Session 不存在，loadIfExists 直接跳过）
                boolean loaded = reActAgent.loadIfExists(session, sessionKey);
                if (loaded) {
                    logger.debug("[SessionPersistence] 已加载历史记忆: sessionId={}, agent={}", sessionId, agentName);
                }
            }
        } else {
            reActAgent.saveTo(session, sessionKey);
            logger.debug("[SessionPersistence] 已保存对话记忆: sessionId={}, agent={}", sessionId, agentName);
        }
    }

    /**
     * 无状态 AgentBase 历史消息在 Session 中的存储 key，与 ReActAgent Memory 的约定保持一致。
     */
    @Deprecated
    private static final String MEMORY_MESSAGES_KEY = "memory_messages";

    /**
     * 无状态 AgentBase 保存的历史消息条数上限，超过后从头部裁剪，避免无限增长。
     */
    @Deprecated
    private static final int MAX_HISTORY_MESSAGES = 20;

    /**
     * 非 ReActAgent 的 AgentBase（单次调用型分析 Agent，如 QueryRewritingAgent、IntentRecognitionAgent）
     * 的 Session 持久化。
     *
     * @Deprecated link: https://thoughts.aliyun.com/workspaces/6963289eb0fc2e001bb052eb/docs/6a5e0000c71a8900017cf153
     */
    @Deprecated
    private void persistStatelessAgent(HookEvent event, String sessionKey, String sessionId,
                                       String agentName, boolean resuming) {
        SessionKey key = SimpleSessionKey.of(sessionKey);

        if (event instanceof PreCallEvent preCallEvent) {
            if (resuming) {
                logger.debug("[SessionPersistence] 恢复暂停 Agent，跳过历史记忆加载: sessionId={}, agent={}", sessionId, agentName);
                return;
            }
            List<Msg> history = session.exists(key)
                    ? session.getList(key, MEMORY_MESSAGES_KEY, Msg.class)
                    : List.<Msg>of();
            List<Msg> merged = new ArrayList<>(history);
            merged.addAll(preCallEvent.getInputMessages());
            if (!history.isEmpty()) {
                preCallEvent.setInputMessages(merged);
                logger.debug("[SessionPersistence] 已加载历史记忆（无状态 Agent）: sessionId={}, agent={}, historySize={}",
                        sessionId, agentName, history.size());
            }
            // 暂存本轮「历史 + 输入」，供 PostCall 追加回复后一并写回 Session
            pendingStatelessInput.put(sessionKey, merged);
        } else {
            PostCallEvent postCallEvent = (PostCallEvent) event;
            List<Msg> merged = pendingStatelessInput.remove(sessionKey);
            List<Msg> newHistory = merged != null ? new ArrayList<>(merged) : new ArrayList<>();
            newHistory.add(postCallEvent.getFinalMessage());
            if (newHistory.size() > MAX_HISTORY_MESSAGES) {
                newHistory = new ArrayList<>(
                        newHistory.subList(newHistory.size() - MAX_HISTORY_MESSAGES, newHistory.size()));
            }
            session.save(key, MEMORY_MESSAGES_KEY, newHistory);
            logger.debug("[SessionPersistence] 已保存对话记忆（无状态 Agent）: sessionId={}, agent={}, size={}",
                    sessionId, agentName, newHistory.size());
        }
    }
}
