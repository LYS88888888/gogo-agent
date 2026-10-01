package com.gogo.travel.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 对象存储配置。
 *
 * <p>启动时自动创建配置的 bucket（如不存在），避免首次部署时手动初始化。
 *
 */
@Configuration
public class MinioConfig {

    private static final Logger logger = LoggerFactory.getLogger(MinioConfig.class);

    @Value("${app.minio.endpoint}")
    private String endpoint;

    @Value("${app.minio.access-key}")
    private String accessKey;

    @Value("${app.minio.secret-key}")
    private String secretKey;

    @Value("${app.minio.bucket}")
    private String bucket;

    @Bean
    public MinioClient minioClient() {
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        logger.info("[MinIO] 客户端已创建, endpoint={}", endpoint);
        return client;
    }

    @PostConstruct
    public void initBucket() {
        try {
            MinioClient client = minioClient();
            boolean exists = client.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                logger.info("[MinIO] bucket '{}' 已创建", bucket);
            } else {
                logger.info("[MinIO] bucket '{}' 已存在", bucket);
            }
        } catch (Exception e) {
            logger.warn("[MinIO] 初始化 bucket 失败（服务可能未启动），后续上传时再处理: {}", e.getMessage());
        }
    }
}
