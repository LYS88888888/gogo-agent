package com.gogo.travel.agent.context;

import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * {@link AgentSessionContext} 在 Reactor Context 上的表示，只负责「写入」与「读取」两件事。
 *
 * <p>与 {@link AgentSessionContextHolder} 是<b>两套互不相干的传播机制</b>，故分开两个类：
 * <ul>
 *   <li>{@link AgentSessionContextHolder}：以<b>线程</b>为投递单位（ThreadLocal），
 *       服务 Agent {@code build()} 这类「组装阶段的同步代码」——那时还没有任何订阅；</li>
 *   <li>本类：以<b>订阅</b>为传播单位，服务 Hook 这类「执行阶段的响应式回调」——
 *       它跨线程、跨子 Agent 边界，但在框架裸 {@code subscribe()} 处会断。</li>
 * </ul>
 *
 */
public final class SessionReactorContext {

    /**
     * 承载 {@link AgentSessionContext} 实例的 key。
     *
     * <p>{@code sessionId} / {@code userId} 不再单独存一份，统一从实例上取，
     * 避免同一份信息在 Context 里有两个来源。
     */
    private static final String KEY_SESSION_CTX = "sessionCtx";

    /**
     * 标记本次订阅是否为「恢复模式」的 key。
     *
     * <p>它的语义是「<b>本次这一条订阅链</b>是恢复模式」，属于订阅级控制信号而非会话数据，
     * 因此不放在 {@link AgentSessionContext} 上，而是作为独立的 Context 项。
     */
    private static final String KEY_RESUMING = "resuming";

    private SessionReactorContext() {}

    /**
     * 由会话上下文实例构造 Reactor Context，是所有 {@code contextWrite(...)} 的唯一入口。
     *
     * @param sessionCtx 本次执行的会话上下文实例，不可为 null
     * @return 可直接传给 {@code contextWrite} 的 Reactor Context
     */
    public static Context of(AgentSessionContext sessionCtx) {
        return Context.of(KEY_SESSION_CTX, sessionCtx);
    }

    /**
     * 同 {@link #of(AgentSessionContext)}，并额外标记是否为恢复模式。
     *
     * <p>恢复模式下 {@code SessionPersistenceHook} 会跳过历史记忆加载：
     * 本地命中的 Agent 实例内存里还持有 pending tool 状态，加载历史会覆盖当前执行上下文；
     * 而跨节点重建的新实例内存是空的，反而需要加载历史。
     *
     * @param sessionCtx 本次执行的会话上下文实例，不可为 null
     * @param resuming   true 表示续跑内存中已有的 Agent 实例
     */
    public static Context of(AgentSessionContext sessionCtx, boolean resuming) {
        Context ctx = of(sessionCtx);
        return resuming ? ctx.put(KEY_RESUMING, Boolean.TRUE) : ctx;
    }

    /**
     * 从 Reactor Context 读取本次执行的会话上下文，与 {@link #of} 对称。
     *
     * <p>主链事件（Pre/PostCall、Pre/PostReasoning、Pre/PostActing）上必然有值；
     * 流式 chunk 类事件因框架用裸 {@code subscribe()} 触发、Context 为空，返回 {@code null}。
     *
     * @param ctx {@code Mono.deferContextual} 提供的 Reactor Context
     * @return 会话上下文实例，Context 中不存在时返回 {@code null}
     */
    public static AgentSessionContext from(ContextView ctx) {
        return ctx.getOrDefault(KEY_SESSION_CTX, null);
    }

    /**
     * 本次订阅是否为恢复模式，与 {@link #of(AgentSessionContext, boolean)} 对称。
     *
     * @param ctx {@code Mono.deferContextual} 提供的 Reactor Context
     */
    public static boolean isResuming(ContextView ctx) {
        return Boolean.TRUE.equals(ctx.getOrDefault(KEY_RESUMING, Boolean.FALSE));
    }
}
