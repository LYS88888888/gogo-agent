package com.gogo.travel.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * rgh 本地工作区清理任务。
 *
 * <p>{@link RghWorkspace} 下的 Token 与登录输出都是明文敏感残留，而 Redis 才是权威源，
 * 本地文件丢失只会导致下次命令重新从 Redis 投影，因此可以放心按时间回收。若不清理，
 * 每个用户在每个曾经服务过他的节点上都会留下一份长期不变的明文 Token，静态暴露面随节点数增长。</p>
 *
 * <p>两级保留期：
 * <ul>
 *   <li>登录输出文件（含授权链接）—— 超过 {@code travel.rgh.login-output-retention} 即删，默认 1 小时；</li>
 *   <li>整个用户目录 —— 最近修改时间超过 {@code travel.rgh.workspace-retention} 即删，默认 7 天。</li>
 * </ul>
 *
 */
@Component
public class RghWorkspaceCleaner {

    private static final Logger logger = LoggerFactory.getLogger(RghWorkspaceCleaner.class);

    @Autowired
    private RghWorkspace workspace;

    /** 用户目录保留时长（秒），默认 7 天：足够覆盖连续出差场景，又不会长期堆积 */
    @Value("${travel.rgh.workspace-retention-seconds:604800}")
    private long workspaceRetentionSeconds;

    /** 登录输出文件保留时长（秒），默认 1 小时：授权窗口只有几分钟，过期即无用 */
    @Value("${travel.rgh.login-output-retention-seconds:3600}")
    private long loginOutputRetentionSeconds;

    /**
     * 每天 03:15 清理一次。选择凌晨低峰，避免与用户操作竞争同一目录。
     */
    @Scheduled(cron = "${travel.rgh.cleanup-cron:0 15 3 * * ?}")
    public void cleanup() {
        Path baseDir = workspace.baseDir();
        if (!Files.isDirectory(baseDir)) {
            return;
        }

        Instant dirDeadline = Instant.now().minus(Duration.ofSeconds(workspaceRetentionSeconds));
        Instant outputDeadline = Instant.now().minus(Duration.ofSeconds(loginOutputRetentionSeconds));
        int removedDirs = 0;
        int removedOutputs = 0;

        try (Stream<Path> userDirs = Files.list(baseDir)) {
            for (Path userDir : userDirs.filter(Files::isDirectory).toList()) {
                try {
                    if (isOlderThan(userDir, dirDeadline)) {
                        deleteRecursively(userDir);
                        removedDirs++;
                        continue;
                    }
                    Path loginOutput = userDir.resolve(RghWorkspace.LOGIN_OUTPUT_FILE);
                    if (Files.exists(loginOutput) && isOlderThan(loginOutput, outputDeadline)) {
                        Files.deleteIfExists(loginOutput);
                        removedOutputs++;
                    }
                } catch (Exception e) {
                    logger.warn("[RghCleanup] 清理用户目录失败: {}", userDir, e);
                }
            }
        } catch (Exception e) {
            logger.warn("[RghCleanup] 遍历工作区失败: {}", baseDir, e);
            return;
        }

        if (removedDirs > 0 || removedOutputs > 0) {
            logger.info("[RghCleanup] 工作区清理完成, 删除过期用户目录={}, 删除过期登录输出={}",
                    removedDirs, removedOutputs);
        }
    }

    /**
     * 以「目录内最新修改时间」判断是否过期：只要用户还在用，Token 文件就会被反复重写，
     * 直接看目录自身的 mtime 在部分文件系统上不会随子文件更新而变化，故递归取最大值。
     */
    private boolean isOlderThan(Path path, Instant deadline) throws IOException {
        if (Files.isRegularFile(path)) {
            return Files.getLastModifiedTime(path).toInstant().isBefore(deadline);
        }
        try (Stream<Path> walk = Files.walk(path)) {
            Instant latest = walk.map(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant();
                        } catch (IOException e) {
                            return Instant.EPOCH;
                        }
                    })
                    .max(Comparator.naturalOrder())
                    .orElse(Instant.EPOCH);
            return latest.isBefore(deadline);
        }
    }

    /** 自底向上删除目录树（先文件后目录）。 */
    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
