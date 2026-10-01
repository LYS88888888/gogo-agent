package com.gogo.travel.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * rgh CLI 的用户级本地工作区。
 *
 * <p>每个用户对应 {@code <base>/{userId}/} 一个目录，作为 rgh 命令执行时的 {@code HOME}：
 * <ul>
 *   <li>{@code <base>/{userId}/.hotel-cli/token.json} —— rgh CLI 读写的 Token 文件</li>
 *   <li>{@code <base>/{userId}/rgh_login_output.txt} —— 后台登录进程输出（含授权链接）</li>
 * </ul>
 *
 * <p>本地文件仅是 Redis 权威源的临时投影，属于敏感残留，因此：目录权限 {@code 700}、
 * 文件权限 {@code 600}，且由 {@link RghWorkspaceCleaner} 定期清理过期目录。</p>
 *
 * <p>{@code userId} 会经白名单字符校验后才拼入路径，避免 {@code ../} 之类的路径穿越。</p>
 *
 */
@Component
public class RghWorkspace {

    private static final Logger logger = LoggerFactory.getLogger(RghWorkspace.class);

    private static final String TOKEN_DIR = ".hotel-cli";
    private static final String TOKEN_FILE = "token.json";
    /** 后台登录输出文件名；置于用户目录下而非全局 /tmp，避免多用户互相覆盖或读到他人授权链接 */
    public static final String LOGIN_OUTPUT_FILE = "rgh_login_output.txt";

    /** 只允许常规标识符字符进入路径，杜绝路径穿越 */
    private static final Pattern SAFE_USER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final Set<PosixFilePermission> DIR_PERMS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS = PosixFilePermissions.fromString("rw-------");

    @Value("${travel.rgh.workspace:/tmp/gogo-agent/rgh-users}")
    private String basePath;

    /** 工作区根目录，供清理任务遍历。 */
    public Path baseDir() {
        return Path.of(basePath);
    }

    /**
     * 解析用户目录（不创建）。
     *
     * @throws IllegalArgumentException userId 含非法字符时
     */
    public Path userDir(String userId) {
        if (userId == null || !SAFE_USER_ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("非法 userId，拒绝用于文件路径: " + userId);
        }
        return baseDir().resolve(userId);
    }

    /**
     * 创建用户目录与 Token 子目录（幂等），并收紧权限。
     *
     * @return 用户目录，即注入给 rgh 的 {@code HOME}
     */
    public Path prepareUserDir(String userId) throws IOException {
        Path userDir = userDir(userId);
        Files.createDirectories(userDir.resolve(TOKEN_DIR));
        restrictPermissions(userDir, DIR_PERMS);
        restrictPermissions(userDir.resolve(TOKEN_DIR), DIR_PERMS);
        return userDir;
    }

    /** Token 文件路径（不保证存在）。 */
    public Path tokenFile(String userId) {
        return userDir(userId).resolve(TOKEN_DIR).resolve(TOKEN_FILE);
    }

    /** 读取本地 Token；文件不存在或内容为空时返回 null。 */
    public String readToken(String userId) {
        Path file = tokenFile(userId);
        try {
            if (!Files.exists(file)) {
                return null;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return content.isBlank() ? null : content;
        } catch (Exception e) {
            logger.warn("[RghWorkspace] 读取本地 Token 失败, userId={}", userId, e);
            return null;
        }
    }

    /** 写入本地 Token 并收紧文件权限。 */
    public void writeToken(String userId, String tokenJson) throws IOException {
        Path file = tokenFile(userId);
        Files.createDirectories(file.getParent());
        Files.writeString(file, tokenJson, StandardCharsets.UTF_8);
        restrictPermissions(file, FILE_PERMS);
    }

    /** 删除本地 Token 文件（幂等）。 */
    public void deleteToken(String userId) {
        deleteQuietly(tokenFile(userId), userId, "Token");
    }

    /** 删除后台登录输出文件（幂等）。内含授权链接，用完即删。 */
    public void deleteLoginOutput(String userId) {
        deleteQuietly(userDir(userId).resolve(LOGIN_OUTPUT_FILE), userId, "登录输出");
    }

    private void deleteQuietly(Path file, String userId, String label) {
        try {
            if (Files.deleteIfExists(file)) {
                logger.debug("[RghWorkspace] 已删除本地{}文件, userId={}", label, userId);
            }
        } catch (Exception e) {
            logger.warn("[RghWorkspace] 删除本地{}文件失败, userId={}", label, userId, e);
        }
    }

    /**
     * 收紧权限；非 POSIX 文件系统（如 Windows）不支持时静默跳过。
     */
    private void restrictPermissions(Path path, Set<PosixFilePermission> perms) {
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException e) {
            logger.debug("[RghWorkspace] 当前文件系统不支持 POSIX 权限，跳过: {}", path);
        } catch (Exception e) {
            logger.warn("[RghWorkspace] 设置文件权限失败: {}", path, e);
        }
    }
}
