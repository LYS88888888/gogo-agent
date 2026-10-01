package com.gogo.travel.agent.context;

import java.util.function.Supplier;

/**
 * 线程本地上下文持有者，用于在 Spring prototype bean 构建时传递 {@link AgentSessionContext}。
 */
public final class AgentSessionContextHolder {

    private static final ThreadLocal<AgentSessionContext> HOLDER = new ThreadLocal<>();

    private AgentSessionContextHolder() {}

    public static void set(AgentSessionContext context) {
        HOLDER.set(context);
    }

    public static AgentSessionContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * @see https://thoughts.aliyun.com/workspaces/6963289eb0fc2e001bb052eb/docs/6a75bd39c71a89000195e6bf
     * @return 已绑定上下文的 supplier；当前线程没有上下文时原样返回入参
     */
    public static <T> Supplier<T> bind(Supplier<T> supplier) {
        AgentSessionContext captured = get();
        if (captured == null) {
            return supplier;
        }
        return () -> callWith(captured, supplier);
    }

    /**
     * 在指定会话上下文下执行逻辑，执行完毕后还原当前线程原有的上下文。
     *
     * @param context  要绑定的会话上下文；为 {@code null} 时直接执行
     * @param supplier 待执行逻辑
     */
    public static <T> T callWith(AgentSessionContext context, Supplier<T> supplier) {
        if (context == null) {
            return supplier.get();
        }
        AgentSessionContext previous = get();
        if (previous == context) {
            return supplier.get();
        }
        set(context);
        try {
            return supplier.get();
        } finally {
            if (previous != null) {
                set(previous);
            } else {
                clear();
            }
        }
    }
}
