package com.gogo.travel.agent.memory;

import com.gogo.travel.config.sensitive.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * rgh CLI 用户 Token 隔离存储。
 *
 * <p>将每个用户的 OAuth Token 存储在 Redis 中，Key 为 {@code rgh:token:{userId}}，
 * 解决多用户共享同一 rgh CLI 实例时 Token 互相覆盖的问题，同时让 Token 可跨节点共享。</p>
 *
 * <p>Token 是比 API Key 更敏感的登录凭据，因此落 Redis 前统一经 {@link SecretCipher}
 * 加密（AES-256-GCM），不得明文存储。为兼容加密上线前已存在的明文数据，
 * {@link #getToken(String)} 在解密失败且内容看起来是 JSON 时会按存量明文读取并自动重写为密文。</p>
 *
 */
@Component
public class RghTokenStore {

    private static final Logger logger = LoggerFactory.getLogger(RghTokenStore.class);

    private static final String KEY_PREFIX = "rgh:token:";
    /** Token 有效期 30 天，与 rgh OAuth Token 默认有效期对齐 */
    private static final Duration TOKEN_TTL = Duration.ofDays(30);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private SecretCipher secretCipher;

    /**
     * 获取指定用户的 rgh Token（解密后的 JSON 字符串）。
     *
     * @return Token JSON，若不存在或无法识别返回 null
     */
    public String getToken(String userId) {
        if (userId == null) {
            return null;
        }
        try {
            String stored = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
            if (stored == null) {
                return null;
            }
            String decrypted = secretCipher.decrypt(stored);
            if (decrypted != null) {
                logger.debug("[RghTokenStore] 命中 Redis Token, userId={}", userId);
                return decrypted;
            }
            // 存量明文数据（加密上线前写入）：读得后立即重写为密文，下次即走正常路径
            if (stored.stripLeading().startsWith("{")) {
                logger.info("[RghTokenStore] 检测到存量明文 Token，已自动重写为密文, userId={}", userId);
                saveToken(userId, stored);
                return stored;
            }
            logger.warn("[RghTokenStore] Token 既无法解密也不是明文 JSON，按未登录处理, userId={}", userId);
            return null;
        } catch (Exception e) {
            logger.warn("[RghTokenStore] 从 Redis 读取 Token 失败, userId={}", userId, e);
            return null;
        }
    }

    /**
     * 保存指定用户的 rgh Token 到 Redis（加密存储）。
     */
    public void saveToken(String userId, String tokenJson) {
        if (userId == null || tokenJson == null) {
            return;
        }
        try {
            redisTemplate.opsForValue()
                    .set(KEY_PREFIX + userId, secretCipher.encrypt(tokenJson), TOKEN_TTL);
            logger.info("[RghTokenStore] Token 已加密保存到 Redis, userId={}", userId);
        } catch (Exception e) {
            logger.warn("[RghTokenStore] 保存 Token 到 Redis 失败, userId={}", userId, e);
        }
    }

    /**
     * 检查指定用户是否已保存 Token。
     */
    public boolean hasToken(String userId) {
        if (userId == null) {
            return false;
        }
        try {
            Boolean exists = redisTemplate.hasKey(KEY_PREFIX + userId);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            logger.warn("[RghTokenStore] 检查 Token 是否存在失败, userId={}", userId, e);
            return false;
        }
    }

    /**
     * 删除指定用户的 Token（用于退出登录）。
     */
    public void deleteToken(String userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + userId);
            logger.info("[RghTokenStore] Token 已从 Redis 删除, userId={}", userId);
        } catch (Exception e) {
            logger.warn("[RghTokenStore] 删除 Token 失败, userId={}", userId, e);
        }
    }
}
