package com.gogo.travel.config.sensitive;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 通用敏感数据对称加密组件。
 *
 * <p>采用 <b>AES-256-GCM</b>：随机 12 字节 IV 拼在密文前，整体 Base64 编码。相比 ECB 模式，
 * GCM 带 IV（相同明文每次密文不同）且自带完整性校验（密文被篡改时解密直接失败）。</p>
 *
 * <p>密钥由 {@code app.api-key-encrypt-secret} 经 SHA-256 派生，与 {@code ApiKeyService} 共用同一份
 * 运维配置，避免多套密钥管理。生产环境务必通过环境变量 {@code API_KEY_ENCRYPT_SECRET} 覆盖默认值。</p>
 *
 * <p>解密失败返回 {@code null} 而非抛异常：调用方多为「读缓存/读凭据」场景，
 * 密钥轮换或数据损坏时应降级为「无凭据」而不是让整条请求 500。</p>
 *
 */
@Component
public class SecretCipher {

    private static final Logger logger = LoggerFactory.getLogger(SecretCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    /** GCM 推荐 IV 长度（字节） */
    private static final int IV_LENGTH = 12;
    /** GCM 认证标签长度（位） */
    private static final int TAG_LENGTH_BIT = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.api-key-encrypt-secret:gogo-travel-default-secret}")
    private String encryptSecret;

    private SecretKeySpec aesKey;

    @PostConstruct
    public void init() {
        // SHA-256 输出正好 32 字节，直接作为 AES-256 密钥
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(encryptSecret.getBytes(StandardCharsets.UTF_8));
            this.aesKey = new SecretKeySpec(hash, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("AES 密钥派生失败", e);
        }
    }

    /**
     * 加密明文。
     *
     * @return Base64(IV + 密文)；入参为 null 时返回 null
     */
    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 加密失败", e);
        }
    }

    /**
     * 解密密文。
     *
     * @return 明文；入参为 null/空、或密文非本组件产出（密钥不匹配、被篡改、明文残留）时返回 null
     */
    public String decrypt(String base64Encrypted) {
        if (base64Encrypted == null || base64Encrypted.isBlank()) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(base64Encrypted);
            if (combined.length <= IV_LENGTH) {
                return null;
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] plain = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 常见于：存量明文数据、密钥轮换后的旧密文。降级为 null 由调用方决定回退策略
            logger.debug("[SecretCipher] 解密失败，按无有效密文处理: {}", e.getMessage());
            return null;
        }
    }
}
