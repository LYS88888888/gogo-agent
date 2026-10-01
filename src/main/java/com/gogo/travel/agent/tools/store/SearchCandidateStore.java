package com.gogo.travel.agent.tools.store;

import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索候选结果的 Redis 存储层。
 *
 */
@Component
public class SearchCandidateStore {

    private static final Logger logger = LoggerFactory.getLogger(SearchCandidateStore.class);

    private static final String KEY_PREFIX = "planner:search:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public SearchCandidateStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 计算按用户隔离的 Redis Key。
     */
    public static String keyOf(String userId) {
        String uid = (userId == null || userId.trim().isEmpty())
                ? "default"
                : userId.trim().replaceAll("[^a-zA-Z0-9_.-]", "_");
        return KEY_PREFIX + uid;
    }

    /**
     * 将一条搜索结果写入 Redis hash。
     *
     * @param userId 用户 ID
     * @param field  hash field 名（由调用方按路由生成）
     * @param entry  搜索结果 JSON
     */
    public void put(String userId, String field, JSONObject entry) {
        String key = keyOf(userId);
        redisTemplate.opsForHash().put(key, field, entry.toJSONString());
        redisTemplate.expire(key, TTL);
        logger.info("[SearchCandidateStore] 已存储搜索结果: key={}, field={}", key, field);
    }

    /**
     * 读取单个 hash field 的值。
     *
     * @param userId 用户 ID
     * @param field  hash field 名
     * @return JSON 字符串，不存在时返回 null
     */
    public String get(String userId, String field) {
        String key = keyOf(userId);
        Object value = redisTemplate.opsForHash().get(key, field);
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * 按 field 名称列表精确批量查询（HMGET）。
     *
     * @param userId 用户 ID
     * @param fields field 名称集合
     * @return field → JSON 字符串的映射，不存在的 field 不会出现在结果中
     */
    public Map<String, String> multiGet(String userId, Collection<String> fields) {
        String key = keyOf(userId);
        List<Object> values = redisTemplate.opsForHash().multiGet(key, List.copyOf(fields));
        Map<String, String> result = new LinkedHashMap<>();
        if (values != null) {
            int i = 0;
            for (String field : fields) {
                Object v = values.get(i++);
                if (v != null) {
                    result.put(field, String.valueOf(v));
                }
            }
        }
        logger.info("[SearchCandidateStore] HMGET: key={}, requested={}, hits={}", key, fields.size(), result.size());
        return result;
    }

    /**
     * 计算机票 hash field 名。
     * 规则：flight_{departureCity}_{arrivalCity}_{date}
     */
    public static String flightField(String departureCity, String arrivalCity, String date) {
        return "flight_" + departureCity + "_" + arrivalCity + "_" + date;
    }

    /**
     * 计算火车票 hash field 名。
     * 规则：train_{departureCity}_{arrivalCity}_{date}
     */
    public static String trainField(String departureCity, String arrivalCity, String date) {
        return "train_" + departureCity + "_" + arrivalCity + "_" + date;
    }

    /**
     * 计算酒店 hash field 名。
     * 规则：hotel_{city}_{checkIn}_{checkOut}
     */
    public static String hotelField(String city, String checkIn, String checkOut) {
        return "hotel_" + city + "_" + checkIn + "_" + checkOut;
    }

    /**
     * 根据行程参数拼接所有可能的 field 名，通过 HMGET 一次性精确查询。
     * 包含去程交通、返程交通、目的地酒店。
     *
     * @param userId        用户 ID
     * @param origin        出发地城市
     * @param destination   目的地城市
     * @param departureDate 去程日期
     * @param returnDate    返程日期
     * @return field → JSON 字符串的映射，不存在的 field 不会出现在结果中
     */
    public Map<String, String> loadByTrip(String userId, String origin, String destination,
                                          String departureDate, String returnDate) {
        List<String> fields = List.of(
                flightField(origin, destination, departureDate),
                trainField(origin, destination, departureDate),
                flightField(destination, origin, returnDate),
                trainField(destination, origin, returnDate),
                hotelField(destination, departureDate, returnDate)
        );
        logger.info("[SearchCandidateStore] loadByTrip: userId={}, fields={}", userId, fields);
        return multiGet(userId, fields);
    }

    /**
     * 清空指定用户的搜索结果。
     */
    public void clear(String userId) {
        String key = keyOf(userId);
        redisTemplate.delete(key);
        logger.info("[SearchCandidateStore] 已清空搜索结果: key={}", key);
    }
}
