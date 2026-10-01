package com.gogo.travel.agent.tools.model;

import com.gogo.travel.config.TravelPolicyProperties;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 城市间交通时长估算服务。todo 这个服务可以改用skill或者工具来实际查询城际交通用时。
 *
 * <p>用于行程冲突检测中判断"用户是否能在两个差旅单之间完成跨城交通"。
 * 数据来源优先级：</p>
 * <ol>
 *   <li>显式配置的 {@code cityPairMinutes}（覆盖默认推断）</li>
 *   <li>城市分层推断（一线/新一线/二线/其他，配置来自 {@link TravelPolicyProperties}）</li>
 *   <li>兜底：4 小时</li>
 * </ol>
 *
 * <p>单位：分钟。包含通勤（去车站/机场）+ 候车/候机 + 在途 + 离站全过程的最小时长。</p>
 *
 */
@Service
public class CityTransitTimeService {

    private static final Logger logger = LoggerFactory.getLogger(CityTransitTimeService.class);

    /** 兜底时长：4 小时（覆盖大多数城际航班/高铁） */
    private static final int DEFAULT_MINUTES = 240;

    /** 同城视为 0 */
    private static final int SAME_CITY_MINUTES = 0;

    /** 含其他/未知城市：6 小时 */
    private static final int TIER_OTHER_MINUTES = 360;

    /** 含二线城市：5 小时 */
    private static final int TIER2_MINUTES = 300;

    /** 一线 / 新一线城市互达：4.5 小时 */
    private static final int TIER1_MINUTES = 270;

    @Autowired
    private TravelPolicyProperties policyProperties;
    private Map<String, Integer> cityPairMinutes;

    @PostConstruct
    public void init() {
        Map<String, Integer> configured = policyProperties.getCityPairMinutes() == null
                ? Map.of() : policyProperties.getCityPairMinutes();
        // 把每条配置按归一化城市名注册正反两个方向，查找与配置书写顺序无关，
        // 避免早期 HashSet 拼 key 导致的“配置了却查不中”问题
        Map<String, Integer> expanded = new HashMap<>();
        for (Map.Entry<String, Integer> e : configured.entrySet()) {
            String[] parts = e.getKey().split("-");
            if (parts.length != 2 || e.getValue() == null || e.getValue() <= 0) {
                logger.warn("[CityTransitTime] 忽略非法 city-pair 配置：{}={}（key 应为 \"城市A-城市B\"，值为正分钟数）",
                        e.getKey(), e.getValue());
                continue;
            }
            String a = normalize(parts[0]);
            String b = normalize(parts[1]);
            expanded.put(a + "-" + b, e.getValue());
            expanded.put(b + "-" + a, e.getValue());
        }
        this.cityPairMinutes = expanded;
        logger.info("[CityTransitTime] 已加载显式 city-pair 配置 {} 对（含正反方向共 {} 条）",
                configured.size(), expanded.size());
    }

    /**
     * 估算从 fromCity 到 toCity 的最短衔接时长（分钟）。
     * 任一端为 null/空时返回 {@link #DEFAULT_MINUTES}（保守兜底，会触发 MEDIUM 提示而非放行）并记 WARN。
     */
    public int estimateMinutes(String fromCity, String toCity) {
        if (isBlank(fromCity) || isBlank(toCity)) {
            // 候选单入参在工具层已强校验，走到这里意味着已有单存在脏数据
            logger.warn("[CityTransitTime] 城市入参为空（from={}, to={}），按兜底 {} 分钟估算，请检查差旅单数据",
                    fromCity, toCity, DEFAULT_MINUTES);
            return DEFAULT_MINUTES;
        }
        String from = normalize(fromCity);
        String to = normalize(toCity);
        if (from.equals(to)) {
            return SAME_CITY_MINUTES;
        }
        Integer explicit = cityPairMinutes.get(from + "-" + to);
        if (explicit != null) {
            return explicit;
        }
        return estimateByTier(fromCity, toCity);
    }

    /**
     * 城市分层推断时长。
     * <ul>
     *   <li>一线↔一线：4.5 小时</li>
     *   <li>含新一线：4.5 小时</li>
     *   <li>含二线：5 小时</li>
     *   <li>其他/未知：6 小时</li>
     * </ul>
     */
    private int estimateByTier(String fromCity, String toCity) {
        CityTier from = tierOf(fromCity);
        CityTier to = tierOf(toCity);
        int maxRank = Math.max(from.rank(), to.rank());
        if (maxRank == CityTier.OTHER.rank()) {
            return TIER_OTHER_MINUTES;
        }
        if (maxRank == CityTier.TIER2.rank()) {
            return TIER2_MINUTES;
        }
        return TIER1_MINUTES;
    }

    private CityTier tierOf(String city) {
        String n = normalize(city);
        if (contains(policyProperties.getTier1Cities(), n)) {
            return CityTier.TIER1;
        }
        if (contains(policyProperties.getNewTier1Cities(), n)) {
            return CityTier.NEW_TIER1;
        }
        if (contains(policyProperties.getTier2Cities(), n)) {
            return CityTier.TIER2;
        }
        return CityTier.OTHER;
    }

    private boolean contains(List<String> list, String normalizedCity) {
        if (list == null) {
            return false;
        }
        for (String s : list) {
            if (normalize(s).equals(normalizedCity)) {
                return true;
            }
        }
        return false;
    }

    /** 统一忽略大小写、去除首尾空白与“市”后缀 */
    private String normalize(String city) {
        if (city == null) {
            return "";
        }
        String s = city.trim();
        if (s.endsWith("市") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 返回全量已加载城市分层快照，方便测试断言 */
    public Map<String, CityTier> snapshotTiers() {
        Map<String, CityTier> snap = new HashMap<>();
        for (String c : policyProperties.getTier1Cities()) {
            snap.put(normalize(c), CityTier.TIER1);
        }
        for (String c : policyProperties.getNewTier1Cities()) {
            snap.put(normalize(c), CityTier.NEW_TIER1);
        }
        for (String c : policyProperties.getTier2Cities()) {
            snap.put(normalize(c), CityTier.TIER2);
        }
        return snap;
    }

    public enum CityTier {
        /**
         * 一线城市
         */
        TIER1(0),
        /**
         * 新一线城市
         */
        NEW_TIER1(1),
        /**
         * 二线城市
         */
        TIER2(2),
        /**
         * 其他城市
         */
        OTHER(3);

        private final int rank;

        CityTier(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }
}
