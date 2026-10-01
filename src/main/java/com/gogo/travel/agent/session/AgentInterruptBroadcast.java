package com.gogo.travel.agent.session;

import com.gogo.travel.agent.registry.AgentExecutionRegistry;
import com.gogo.travel.agent.registry.SseEmitterRegistry;
import com.gogo.travel.agent.service.ChatSseNotifier;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 基于 Redis Pub/Sub 的 Agent 优雅中断广播组件。
 *
 * <p>集群模式下，用户的 interrupt 请求可能落在任意节点上，而目标 Agent 实际运行在
 * 另一个节点。本组件让每个节点订阅统一的 {@code agent:interrupt} 频道；收到消息后
 * 每个节点执行完整的本地清理：
 * <ol>
 *   <li>{@link AgentExecutionRegistry#interruptLocal(String)} —— 中断本地运行中的 Agent 并持久化 memory</li>
 *   <li>{@link AgentSessionManager#remove(String)} —— 清理本地缓存的 ask_user suspended 状态</li>
 *   <li>{@link PendingToolSessionStore#clear(String)} —— 清理共享 Session 中的 pending tool 状态</li>
 *   <li>若本节点持有 SseEmitter，发送 interrupted 事件通知前端</li>
 * </ol>
 * 所有操作均为幂等，状态不存在时自动跳过，确保集群中每个节点都尝试清理自己可能持有的状态。</p>
 *
 */
@Component
public class AgentInterruptBroadcast implements MessageListener {

    private static final Logger logger = LoggerFactory.getLogger(AgentInterruptBroadcast.class);

    public static final String INTERRUPT_CHANNEL = "agent:interrupt";

    /** 广播消息中 sessionId 与时间戳的分隔符 */
    private static final String PAYLOAD_SEPARATOR = "|";

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private AgentExecutionRegistry executionRegistry;
    @Autowired
    private RedisMessageListenerContainer listenerContainer;
    @Autowired
    private AgentSessionManager agentSessionManager;
    @Autowired
    private PendingToolSessionStore pendingToolSessionStore;
    @Autowired
    private SseEmitterRegistry emitterRegistry;
    @Autowired
    private ChatSseNotifier sseNotifier;

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new ChannelTopic(INTERRUPT_CHANNEL));
        logger.info("[INTERRUPT_BROADCAST] 已订阅 Redis 频道: {}", INTERRUPT_CHANNEL);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody());
        if (body == null || body.isBlank()) {
            return;
        }
        // 解析 "sessionId|timestamp" 格式；兼容旧格式（无分隔符时整个 body 视为 sessionId）
        String sessionId;
        long broadcastTime;
        int sepIdx = body.lastIndexOf(PAYLOAD_SEPARATOR);
        if (sepIdx > 0) {
            sessionId = body.substring(0, sepIdx);
            try {
                broadcastTime = Long.parseLong(body.substring(sepIdx + 1));
            } catch (NumberFormatException e) {
                broadcastTime = Long.MAX_VALUE;
            }
        } else {
            sessionId = body;
            broadcastTime = Long.MAX_VALUE;
        }
        if (sessionId.isBlank()) {
            return;
        }
        logger.info("[INTERRUPT_BROADCAST] 收到广播中断消息, sessionId={}, broadcastTime={}", sessionId, broadcastTime);
        interruptLocalSession(sessionId, broadcastTime);
    }

    /**
     * 在当前节点执行完整的本地中断与清理（无时序校验，用于本地直接调用）。
     */
    public boolean interruptLocalSession(String sessionId) {
        return interruptLocalSession(sessionId, Long.MAX_VALUE);
    }

    /**
     * 在当前节点执行完整的本地中断与清理，带广播时序校验。
     *
     * <p>包括：
     * <ol>
     *   <li>中断本地运行中的 Agent 并持久化 memory（带时序校验，跳过广播后新启动的 Agent）</li>
     *   <li>清理本地缓存的 ask_user suspended 状态（Caffeine，幂等）</li>
     *   <li>清理共享 Session 中的 pending tool 状态</li>
     *   <li>若本节点持有 SseEmitter（即发起 chat 请求的节点），通知前端"已停止生成"</li>
     * </ol>
     * 所有操作均为幂等，状态不存在时自动跳过。</p>
     *
     * @param broadcastTime 中断广播发出的时间戳（毫秒）；传 {@link Long#MAX_VALUE} 表示不做时序过滤
     * @return true 表示本地命中并中断了运行中的 Agent
     */
    public boolean interruptLocalSession(String sessionId, long broadcastTime) {
        boolean interrupted = executionRegistry.interruptLocal(sessionId, broadcastTime);
        agentSessionManager.remove(sessionId);
        pendingToolSessionStore.clear(sessionId);
        // 仅在本地确实中断了运行中的 Agent 时才通知前端。
        // 否则 Redis Pub/Sub 回环可能在新请求注册新 emitter 后误发 "已停止生成"。
        if (interrupted) {
            SseEmitter emitter = emitterRegistry.get(sessionId);
            if (emitter != null) {
                sseNotifier.sendInterrupted(emitter);
            }
        }
        return interrupted;
    }

    /**
     * 向所有节点广播中断指定 session 的 Agent，消息携带发送时间戳。
     *
     * <p>消息格式：{@code sessionId|timestamp}，接收端据此判断本地 Agent 是否是在
     * 广播发出之后才启动的新执行流，避免异步广播回环误杀新 Agent。</p>
     */
    public void broadcastInterrupt(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        long timestamp = System.currentTimeMillis();
        String payload = sessionId + PAYLOAD_SEPARATOR + timestamp;
        redisTemplate.convertAndSend(INTERRUPT_CHANNEL, payload);
        logger.info("[INTERRUPT_BROADCAST] 已广播中断消息, sessionId={}, timestamp={}", sessionId, timestamp);
    }
}
