package com.gogo.travel.agent.hook;

import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.context.SessionReactorContext;
import com.gogo.travel.agent.memory.RghTokenStore;
import com.gogo.travel.agent.memory.RghWorkspace;
import io.agentscope.core.hook.*;
import io.agentscope.core.message.ToolUseBlock;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * rgh CLI 用户隔离 Hook。
 *
 * <p>rgh CLI 默认把登录 Token 写在全局 {@code ~/.hotel-cli/token.json}，多用户共用一个进程时会互相覆盖。
 * 本 Hook 通过改写 shell 命令为每个用户指定独立 {@code HOME}，实现 Token 目录隔离；
 * Redis 作为跨节点的<b>唯一权威源</b>，本地文件只是当次命令的临时投影。</p>
 *
 * <p>{@link PreActingEvent}：命中 {@code execute_shell_command} 且命令含 {@code rgh} 时——
 * <ol>
 *   <li>Redis 有 Token → 写入用户目录；Redis 无 Token → <b>删除</b>本地残留，使登出/过期在所有节点一致；</li>
 *   <li>把原命令包装为 {@code env HOME=<userDir> bash -c '<原命令>'}；</li>
 *   <li>若是 {@code rgh login}，将命令改写为<b>后台执行</b>（输出重定向到用户隔离文件 + 轮询等待 URL 出现后 cat），
 *       使工具结果在数秒内返回授权链接（而非阻塞到超时）；同时启动后台 watcher 轮询本地 Token 文件，
 *       用户完成浏览器授权后自动回写 Redis。</li>
 * </ol>
 *
 * <p>{@link PostActingEvent}：对<b>任意</b> rgh 命令都比对本地 Token 与本次注入值，发现变化即回写 Redis，
 * 因此 OAuth 刷新（不限于 {@code whoami}）也能被捕获；{@code logout} 则同时清理 Redis 与本地文件。</p>
 *
 */
@Component
public class RghUserIsolationHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(RghUserIsolationHook.class);

    private static final String SHELL_TOOL_NAME = "execute_shell_command";
    private static final String RGH_CMD = "rgh ";

    /**
     * 登录 watcher 的轮询间隔与最长等待时长（用户在浏览器完成授权的合理窗口）
     */
    private static final Duration WATCH_INTERVAL = Duration.ofSeconds(2);
    private static final Duration WATCH_TIMEOUT = Duration.ofMinutes(5);

    @Autowired
    private RghTokenStore tokenStore;

    @Autowired
    private RghWorkspace workspace;

    /**
     * 本轮 rgh 调用的上下文，供 PostActingEvent 判断变更与操作类型
     * （PreActing 改写后命令已被包装，PostActing 无法直接识别原命令）。
     * Key 取 toolUseId 以支持同一 Agent 的并发调用，消费即移除。
     */
    private final Map<String, RghInvocation> invocations = new ConcurrentHashMap<>();

    /**
     * 正在等待授权的用户，避免同一用户重复启动 watcher
     */
    private final Set<String> watchingUsers = ConcurrentHashMap.newKeySet();

    /**
     * 登录 watcher 专用调度器，单线程足够（并发登录量极低且任务是轻量文件轮询）
     */
    private final ScheduledExecutorService watcherExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rgh-login-watcher");
        t.setDaemon(true);
        return t;
    });

    /**
     * 单次 rgh 调用的快照。
     *
     * @param userId      所属用户
     * @param command     改写前的原始命令
     * @param syncedToken 本次注入到本地的 Token 内容（Redis 无值时为 null），用于事后比对是否被 CLI 刷新
     */
    private record RghInvocation(String userId, String command, String syncedToken) {
    }

    @PreDestroy
    public void shutdown() {
        watcherExecutor.shutdownNow();
    }

    @Override
    public int priority() {
        // 数值越小越先执行：晚于 API Key 注入（AbstractShellApiKeyHook=3），早于 SessionPersistenceHook(10)
        return 5;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent preActing) {
            logger.debug("[RghIsolation] PreActingEvent, toolUse={}", preActing.getToolUse());
            return handlePreActing(preActing).map(e -> {
                @SuppressWarnings("unchecked")
                T casted = (T) e;
                return casted;
            });
        }
        if (event instanceof PostActingEvent postActing) {
            return handlePostActing(postActing).map(e -> {
                @SuppressWarnings("unchecked")
                T casted = (T) e;
                return casted;
            });
        }
        return Mono.just(event);
    }

    private Mono<PreActingEvent> handlePreActing(PreActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse == null || !SHELL_TOOL_NAME.equals(toolUse.getName())) {
            return Mono.just(event);
        }

        Map<String, Object> input = toolUse.getInput();
        if (input == null) {
            return Mono.just(event);
        }

        String command = (String) input.get("command");
        if (command == null || !command.contains(RGH_CMD)) {
            return Mono.just(event);
        }

        return Mono.deferContextual(ctx -> {
            AgentSessionContext sessionCtx = SessionReactorContext.from(ctx);
            if (sessionCtx == null) {
                logger.warn("[RghIsolation] Reactor Context 中无会话上下文，跳过用户隔离");
                return Mono.just(event);
            }
            String userId = sessionCtx.getUserId();

            try {
                Path userDir = workspace.prepareUserDir(userId);

                // Redis 是权威源：有值则投影到本地，无值则清掉本地残留，
                // 保证「其他节点已登出 / Token 已过期」能在本节点如实体现为未登录
                String syncedToken = tokenStore.getToken(userId);
                if (syncedToken != null) {
                    workspace.writeToken(userId, syncedToken);
                    logger.debug("[RghIsolation] 已从 Redis 恢复 Token, userId={}", userId);
                } else {
                    workspace.deleteToken(userId);
                }

                // ShellCommandTool 内部执行 bash -c "<wrappedCommand>"，故最终形如：
                // bash -c "env HOME=/tmp/gogo-agent/rgh-users/u_001 bash -c 'rgh whoami'"
                // 单引号需转义，避免原命令中的引号截断外层包装
                String escapedCommand = command.replace("'", "'\\''");
                String wrappedCommand;

                if (isLoginCommand(command)) {
                    // rgh login 是前台阻塞命令（等待用户在浏览器完成授权），直接执行会阻塞到超时
                    // 才返回结果，导致用户看不到授权链接。改写为：
                    // 1. 后台执行 rgh login，输出重定向到用户隔离文件
                    // 2. 轮询输出文件直到授权 URL 出现（最多 15 秒）
                    // 3. cat 输出文件——Agent 可立即将授权链接展示给用户
                    // 4. 后台 watcher（已启动）继续轮询 Token 文件，用户授权后自动回写 Redis
                    Path loginOutput = userDir.resolve(RghWorkspace.LOGIN_OUTPUT_FILE);
                    // 注意：loginInner 会被嵌入 bash -c '...' 的单引号内，因此内部不能再使用单引号；
                    // grep 用 https:// 作为无引号的匹配模式（在 shell 中无需引号），避开引号冲突
                    String loginInner = command
                            + " > " + loginOutput + " 2>&1 &"
                            + " for i in $(seq 1 30); do"
                            + " sleep 0.5;"
                            + " if grep -q https:// " + loginOutput + " 2>/dev/null; then break; fi;"
                            + " done;"
                            + " cat " + loginOutput;
                    wrappedCommand = "env HOME=" + userDir + " bash -c '" + loginInner + "'";
                    logger.info("[RghIsolation] rgh login 已改写为后台执行，URL 将快速返回, userId={}", userId);
                } else {
                    wrappedCommand = "env HOME=" + userDir + " bash -c '" + escapedCommand + "'";
                }

                Map<String, Object> newInput = new HashMap<>(input);
                newInput.put("command", wrappedCommand);

                ToolUseBlock newToolUse = new ToolUseBlock(
                        toolUse.getId(),
                        toolUse.getName(),
                        newInput,
                        toolUse.getContent(),
                        toolUse.getMetadata()
                );

                event.setToolUse(newToolUse);
                invocations.put(invocationKey(toolUse, event), new RghInvocation(userId, command, syncedToken));

                // login 是后台阻塞轮询，Token 会在本次命令返回之后才落盘，
                // 因此必须由 watcher 兜住，否则该 Token 永远不会进 Redis
                if (isLoginCommand(command)) {
                    startLoginWatcher(userId, syncedToken);
                }

                logger.info("[RghIsolation] rgh 命令已注入用户隔离 HOME, userId={}, originalCmd={}",
                        userId, command.length() > 80 ? command.substring(0, 80) + "..." : command);

            } catch (Exception e) {
                logger.error("[RghIsolation] 注入用户隔离环境失败, userId={}", userId, e);
            }

            return Mono.just(event);
        });
    }

    private Mono<PostActingEvent> handlePostActing(PostActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse == null || !SHELL_TOOL_NAME.equals(toolUse.getName())) {
            return Mono.just(event);
        }

        RghInvocation invocation = invocations.remove(invocationKey(toolUse, event));
        if (invocation == null) {
            return Mono.just(event);
        }

        return Mono.deferContextual(ctx -> {
            AgentSessionContext sessionCtx = SessionReactorContext.from(ctx);
            if (sessionCtx == null) {
                return Mono.just(event);
            }
            String userId = sessionCtx.getUserId();

            try {
                if (isLogoutCommand(invocation.command())) {
                    // 登出必须两侧都清：只删 Redis 会让本地残留的 Token 继续把用户认成已登录
                    tokenStore.deleteToken(userId);
                    workspace.deleteToken(userId);
                    workspace.deleteLoginOutput(userId);
                    watchingUsers.remove(userId);
                    logger.info("[RghIsolation] 已退出登录，Redis 与本地 Token 均已清理, userId={}", userId);
                    return Mono.just(event);
                }

                // 任何 rgh 命令都可能触发 CLI 刷新 Token，故一律比对回写，不再只认 whoami
                String localToken = workspace.readToken(userId);
                if (localToken != null && !Objects.equals(localToken, invocation.syncedToken())) {
                    tokenStore.saveToken(userId, localToken);
                    logger.info("[RghIsolation] 检测到本地 Token 变更，已回写 Redis, userId={}", userId);
                }
            } catch (Exception e) {
                logger.error("[RghIsolation] Token 后处理失败, userId={}", userId, e);
            }

            return Mono.just(event);
        });
    }

    /**
     * 启动后台 watcher 轮询本地 Token 文件：一旦后台 {@code rgh login} 进程收到授权并落盘，
     * 立刻回写 Redis，使其他节点也能读到登录态。超时或成功后自行退出。
     *
     * @param baselineToken 启动时的 Token 内容，用于识别"新落盘"（可能为 null）
     */
    private void startLoginWatcher(String userId, String baselineToken) {
        if (!watchingUsers.add(userId)) {
            logger.debug("[RghIsolation] 该用户已有登录 watcher 在运行, userId={}", userId);
            return;
        }

        long deadline = System.currentTimeMillis() + WATCH_TIMEOUT.toMillis();
        logger.info("[RghIsolation] 已启动登录 watcher, userId={}, 最长等待={}分钟",
                userId, WATCH_TIMEOUT.toMinutes());

        watcherExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    String token = workspace.readToken(userId);
                    if (token != null && !Objects.equals(token, baselineToken)) {
                        tokenStore.saveToken(userId, token);
                        watchingUsers.remove(userId);
                        // 授权链接已消费完毕，顺手清掉含链接的输出文件
                        workspace.deleteLoginOutput(userId);
                        logger.info("[RghIsolation] 登录 watcher 捕获到新 Token 并已回写 Redis, userId={}", userId);
                        return;
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        watchingUsers.remove(userId);
                        logger.info("[RghIsolation] 登录 watcher 超时退出（用户未完成授权）, userId={}", userId);
                        return;
                    }
                    watcherExecutor.schedule(this, WATCH_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    watchingUsers.remove(userId);
                    logger.warn("[RghIsolation] 登录 watcher 异常退出, userId={}", userId, e);
                }
            }
        }, WATCH_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 优先用 toolUseId 作为键以支持并发调用，缺失时退回 agentId。
     */
    private String invocationKey(ToolUseBlock toolUse, HookEvent event) {
        if (toolUse.getId() != null) {
            return toolUse.getId();
        }
        if (event instanceof PreActingEvent pre && pre.getAgent() != null) {
            return pre.getAgent().getAgentId();
        }
        if (event instanceof PostActingEvent post && post.getAgent() != null) {
            return post.getAgent().getAgentId();
        }
        return "unknown";
    }

    private boolean isLoginCommand(String command) {
        return command.contains("rgh login") && !command.contains("--help");
    }

    private boolean isLogoutCommand(String command) {
        return command.contains("logout");
    }
}
