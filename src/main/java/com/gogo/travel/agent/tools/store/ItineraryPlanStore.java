package com.gogo.travel.agent.tools.store;

import com.gogo.travel.agent.tools.ItineraryPlannerTools;
import com.gogo.travel.agent.tools.ItineraryReviewTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 往返行程规划结果的 Redis 存储层。
 *
 * <p>封装「写入规划结果」与「读取规划结果」两个操作，供 {@link ItineraryPlannerTools}（写入方）
 * 和 {@link ItineraryReviewTools}（读取方）共用，保证 Key 格式与 TTL 策略统一。</p>
 *
 * <p>Redis 结构：Hash，Key = {@code planner:result:{userId}}，field = {@code {origin}_{destination}_{departureDate}}。
 * 支持同一用户多个行程并存。</p>
 * <p>TTL：24 小时，规划结果属于短期中间数据，超时自动清理。</p>
 *
 */
@Component
public class ItineraryPlanStore {

    private static final Logger logger = LoggerFactory.getLogger(ItineraryPlanStore.class);

    /** Redis Key 前缀。 */
    private static final String KEY_PREFIX = "planner:result:";

    /** 规划结果过期时间：24 小时。 */
    private static final Duration RESULT_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public ItineraryPlanStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 计算按用户隔离的 Redis Key。
     * userId 为空时归入 "default"；对 userId 做安全字符过滤。
     */
    public static String keyOf(String userId) {
        String uid = (userId == null || userId.trim().isEmpty())
                ? "default"
                : userId.trim().replaceAll("[^a-zA-Z0-9_.-]", "_");
        return KEY_PREFIX + uid;
    }

    /**
     * 根据行程参数生成 Hash field 名。
     * 规则：{origin}_{destination}_{departureDate}
     */
    public static String tripKey(String origin, String destination, String departureDate) {
        return origin + "_" + destination + "_" + departureDate;
    }

    /**
     * 将规划结果 JSON 写入 Redis Hash。
     *
     * @param userId        当前用户 ID
     * @param origin        出发地城市
     * @param destination   目的地城市
     * @param departureDate 去程日期
     * @param resultJson    规划结果 JSON 字符串
     */
    public void save(String userId, String origin, String destination, String departureDate, String resultJson) {
        String key = keyOf(userId);
        String field = tripKey(origin, destination, departureDate);
        redisTemplate.opsForHash().put(key, field, resultJson);
        redisTemplate.expire(key, RESULT_TTL);
        logger.info("[ItineraryPlanStore] 已保存规划结果: key={}, field={}", key, field);
    }

    /**
     * 从 Redis Hash 读取指定行程的规划结果 JSON。
     *
     * @param userId        当前用户 ID
     * @param origin        出发地城市
     * @param destination   目的地城市
     * @param departureDate 去程日期
     * @return 规划结果 JSON 字符串；不存在或已过期时返回 null
     */
    public String load(String userId, String origin, String destination, String departureDate) {
        String key = keyOf(userId);
        String field = tripKey(origin, destination, departureDate);
        Object value = redisTemplate.opsForHash().get(key, field);
        if (value == null) {
            logger.info("[ItineraryPlanStore] Redis 中无规划结果: key={}, field={}", key, field);
            return null;
        }
        return String.valueOf(value);
    }
}
