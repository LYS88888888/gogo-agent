package com.gogo.travel.agent.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentSessionContextHolder} 的跨线程语义测试。
 *
 * <p>被测的不是「ThreadLocal 会不会存值」这种平凡行为，而是这个类存在的两条设计约束：
 * <ol>
 *   <li><b>bind 必须有效</b>：{@code SubAgentProvider} 在工具执行线程上才被调用，而它需要的会话
 *       上下文只存在于构建 Agent 的那个线程。{@link AgentSessionContextHolder#bind} 负责把「线程绑定」
 *       翻译成「值捕获」，使子 Agent 的 {@code build()} 能读到正确的上下文。</li>
 *   <li><b>未投递过的线程必须读到 null</b>：{@code BaseSubAgent.createToolCtx()} 依赖 null 来告警并
 *       快速失败。若持有者换成 {@code InheritableThreadLocal} 系（含裸用的 TTL），复用的池化 worker 会
 *       残留上一个会话的上下文，告警消失、跨用户静默串号。{@link #reusedWorkerMustNotRetainPreviousContext()}
 *       就是钉死这一点的回归护栏。</li>
 * </ol>
 *
 * <p>测试统一使用 {@code newSingleThreadExecutor} 而非直接 {@code new Thread}，因为要复现的正是
 * <b>线程复用</b>：worker 只在首次提交时创建一次，之后被反复复用，与 Reactor 的
 * {@code boundedElastic} 行为一致。每个用例都断言两次提交确实落在同一线程上，避免线程没被复用时假通过。
 *
 */
@DisplayName("AgentSessionContextHolder 跨线程上下文投递语义")
class AgentSessionContextHolderTest {

    private static final AgentSessionContext CTX_A = new AgentSessionContext("userA", "sessionA");
    private static final AgentSessionContext CTX_B = new AgentSessionContext("userB", "sessionB");

    @AfterEach
    void tearDown() {
        // 测试跑在 surefire 的线程上，不清理会污染后续用例
        AgentSessionContextHolder.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // bind 的核心能力：把「线程绑定」换成「值捕获」
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bind 捕获构建线程的上下文，使其在另一个线程上执行时依然可见")
    void bindMakesContextVisibleOnAnotherThread() throws Exception {
        AgentSessionContextHolder.set(CTX_A);
        // 模拟 MasterAgent.build() 里的 provider：() -> context.getBean("itineraryManageAgent", ...)
        // 该 lambda 内部会经由 BaseSubAgent.createToolCtx() 读取 ThreadLocal
        Supplier<AgentSessionContext> provider =
                AgentSessionContextHolder.bind(AgentSessionContextHolder::get);
        AgentSessionContextHolder.clear();

        try (Worker worker = new Worker()) {
            assertSame(CTX_A, worker.call(provider::get),
                    "bind 后的 provider 在工具执行线程上未读到构建期捕获的上下文");
        }
    }

    @Test
    @DisplayName("不经 bind 的 provider 在别的线程上读不到上下文，这正是 sessionCtx 为 null 的成因")
    void providerWithoutBindLosesContext() throws Exception {
        AgentSessionContextHolder.set(CTX_A);
        // 对照组：直接把读取逻辑丢过去，不做捕获。lambda 捕获的只是「怎么读」，不含「读什么」
        Supplier<AgentSessionContext> rawProvider = AgentSessionContextHolder::get;

        try (Worker worker = new Worker()) {
            assertNull(worker.call(rawProvider::get),
                    "未经 bind 的 provider 竟在别的线程上读到了上下文，说明持有者带有跨线程传播语义");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 回归护栏：持有者不得具备「线程创建即继承」语义
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("复用的 worker 上裸读必须为 null，不得残留上一个会话的上下文")
    void reusedWorkerMustNotRetainPreviousContext() throws Exception {
        try (Worker worker = new Worker()) {
            // 关键前提：worker 线程在「当前线程已持有 CTX_A」时才被创建。
            // 若 HOLDER 是 InheritableThreadLocal 系（含裸用 TTL），worker 会在构造时继承 CTX_A，
            // 且该副本不会随本线程的 clear() 消失，也不会在后续任务提交时被刷新。
            AgentSessionContextHolder.set(CTX_A);
            Supplier<AgentSessionContext> provider =
                    AgentSessionContextHolder.bind(AgentSessionContextHolder::get);
            assertSame(CTX_A, worker.call(provider::get), "首次执行就没拿到捕获的上下文");

            // 会话 A 结束，请求线程清理自己的 ThreadLocal
            AgentSessionContextHolder.clear();

            // 会话 B 复用同一个 worker，且这次没有任何人向该线程投递上下文。
            // 必须读到 null —— 只有这样 createToolCtx() 才会告警并让工具快速失败，
            // 而不是静默拿着 userA 的身份去查/改 userB 的差旅单。
            assertNull(worker.call(AgentSessionContextHolder::get),
                    "复用的 worker 上残留了上一个会话的上下文，会导致跨用户静默串号；"
                            + "请确认 HOLDER 是普通 ThreadLocal，而非 InheritableThreadLocal/TTL");
        }
    }

    @Test
    @DisplayName("bind 执行完毕后不在执行线程上留下痕迹")
    void bindDoesNotPolluteExecutionThread() throws Exception {
        AgentSessionContextHolder.set(CTX_A);
        Supplier<AgentSessionContext> provider =
                AgentSessionContextHolder.bind(AgentSessionContextHolder::get);
        AgentSessionContextHolder.clear();

        try (Worker worker = new Worker()) {
            assertSame(CTX_A, worker.call(provider::get));
            // provider 执行期间 worker 上被临时 set 过，finally 必须把它还原为「没有值」
            assertNull(worker.call(AgentSessionContextHolder::get),
                    "bind 执行后未还原执行线程原有状态，上下文泄漏到了线程池");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 多会话共用一个 worker：MasterAgent 的真实并发形态
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("两个会话的 provider 交替在同一 worker 上执行，各自拿到自己的上下文")
    void concurrentSessionsDoNotInterfereOnSharedWorker() throws Exception {
        Supplier<AgentSessionContext> providerA = buildProviderOnDedicatedThread(CTX_A);
        Supplier<AgentSessionContext> providerB = buildProviderOnDedicatedThread(CTX_B);

        try (Worker worker = new Worker()) {
            assertSame(CTX_A, worker.call(providerA::get), "会话 A 的 provider 拿错了上下文");
            assertSame(CTX_B, worker.call(providerB::get), "会话 B 的 provider 拿错了上下文");
            // 交替回来，确认 B 的执行没有把 A 的捕获值改掉
            assertSame(CTX_A, worker.call(providerA::get), "交替执行后会话 A 的上下文被污染");
            assertNull(worker.call(AgentSessionContextHolder::get), "交替执行后 worker 上仍有残留");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // callWith 的还原语义与 bind 的退化分支
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("callWith 执行完毕后把线程还原为原有上下文，支持嵌套")
    void callWithRestoresPreviousContext() {
        AgentSessionContextHolder.set(CTX_A);

        AgentSessionContext seenInside = AgentSessionContextHolder.callWith(
                CTX_B, AgentSessionContextHolder::get);

        assertSame(CTX_B, seenInside, "callWith 内部未看到传入的上下文");
        assertSame(CTX_A, AgentSessionContextHolder.get(), "callWith 结束后未还原为原有上下文");
    }

    @Test
    @DisplayName("callWith 传入 null 时直接执行，不改动当前线程上下文")
    void callWithNullContextKeepsCurrent() {
        AgentSessionContextHolder.set(CTX_A);

        assertSame(CTX_A, AgentSessionContextHolder.callWith(null, AgentSessionContextHolder::get),
                "传 null 时应沿用当前线程已有的上下文");
        assertSame(CTX_A, AgentSessionContextHolder.get(), "传 null 时不应改动当前线程上下文");
    }

    @Test
    @DisplayName("构建线程没有上下文时 bind 原样返回入参，不做无意义包装")
    void bindReturnsSameSupplierWhenNoContext() {
        AgentSessionContextHolder.clear();
        Supplier<String> original = () -> "unchanged";

        assertSame(original, AgentSessionContextHolder.bind(original),
                "无上下文可捕获时 bind 应原样返回入参");
    }

    @Test
    @DisplayName("bind 捕获的是构建那一刻的上下文，之后构建线程改值不影响已捕获的 provider")
    void bindSnapshotsContextAtBuildTime() throws Exception {
        AgentSessionContextHolder.set(CTX_A);
        Supplier<AgentSessionContext> provider =
                AgentSessionContextHolder.bind(AgentSessionContextHolder::get);

        // 构建线程后续被复用于别的会话（或被投递了新上下文），已生成的 provider 不应随之漂移
        AgentSessionContextHolder.set(CTX_B);

        try (Worker worker = new Worker()) {
            AgentSessionContext seen = worker.call(provider::get);
            assertSame(CTX_A, seen, "provider 拿到的不是构建期捕获的上下文");
            assertNotSame(CTX_B, seen, "provider 跟随构建线程的后续变更漂移了");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 辅助：复用同一线程的执行器，并保证「线程确实被复用」
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 在一个专用线程上投递上下文并生成 bind 后的 provider，模拟不同会话各自的 Agent 构建线程。
     * 该线程随即退出，确保 provider 后续执行时其原始线程已不存在，只能依赖捕获值。
     */
    private static Supplier<AgentSessionContext> buildProviderOnDedicatedThread(
            AgentSessionContext ctx) throws Exception {
        ExecutorService buildThread = Executors.newSingleThreadExecutor();
        try {
            Future<Supplier<AgentSessionContext>> future = buildThread.submit(() -> {
                AgentSessionContextHolder.set(ctx);
                try {
                    return AgentSessionContextHolder.bind(AgentSessionContextHolder::get);
                } finally {
                    AgentSessionContextHolder.clear();
                }
            });
            return future.get();
        } finally {
            buildThread.shutdown();
        }
    }

    /**
     * 单线程执行器包装：模拟被反复复用的工具执行线程（对应 Reactor 的 boundedElastic worker），
     * 并在每次调用后校验线程确实是同一个，避免「线程没被复用」导致用例假通过。
     */
    private static final class Worker implements AutoCloseable {

        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private Thread firstThread;

        <T> T call(Supplier<T> action) throws InterruptedException, ExecutionException {
            Result<T> result = executor.submit(
                    () -> new Result<>(Thread.currentThread(), action.get())).get();
            if (firstThread == null) {
                firstThread = result.thread();
            } else {
                assertSame(firstThread, result.thread(),
                        "worker 线程未被复用，本用例无法验证线程复用场景");
            }
            assertTrue(result.thread() != Thread.currentThread(),
                    "任务竟在调用线程上执行，无法验证跨线程语义");
            return result.value();
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }

        private record Result<T>(Thread thread, T value) {}
    }
}
