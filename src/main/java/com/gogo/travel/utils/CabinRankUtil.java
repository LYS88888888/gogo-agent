package com.gogo.travel.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 交通舱位/席别等级比较工具。
 *
 * <p>差旅政策中 {@code flightClass} / {@code trainSeatClass} 表示允许的「最高」舱位等级，
 * 低于或等于该等级均视为合规。本工具将各类舱位名称归一化为数值等级进行比较。
 *
 * <p>等级体系（数值越大等级越高）：
 * <ul>
 *   <li>飞机：经济舱=1，商务舱=2，头等舱=3</li>
 *   <li>高铁：二等座=1，一等座=2，商务座=3</li>
 * </ul>
 *
 */
public final class CabinRankUtil {

    private CabinRankUtil() {}

    /** 舱位/席别名称 &rarr; 等级数值 */
    private static final Map<String, Integer> RANK_MAP = Map.ofEntries(
            // ── 飞机舱位（从低到高） ──
            Map.entry("经济舱", 1), Map.entry("economy", 1), Map.entry("经济", 1),
            Map.entry("商务舱", 2), Map.entry("business", 2), Map.entry("商务", 2),
            Map.entry("头等舱", 3), Map.entry("first", 3),    Map.entry("头等", 3),
            // ── 高铁座位（从低到高） ──
            Map.entry("二等座", 1), Map.entry("second", 1),
            Map.entry("一等座", 2),
            Map.entry("商务座", 3)
    );

    /**
     * 获取舱位/席别的等级数值。未知名称返回 -1。
     *
     * @param cabin 舱位/席别名称（中英文均可）
     * @return 等级数值（1-3），未知返回 -1
     */
    static int rankOf(String cabin) {
        if (cabin == null || cabin.isBlank()) {
            return -1;
        }
        return RANK_MAP.getOrDefault(cabin.trim().toLowerCase(), -1);
    }

    /**
     * 判断实际舱位/席别是否合规：实际等级 &le; 允许等级。
     *
     * @param actual      实际舱位/席别
     * @param allowedSpec 政策允许的舱位/席别（可为 "/" 或 "," 分隔的列表）
     * @return 合规返回 {@code true}；缺失数据时不拦截（返回 {@code true}）
     */
    public static boolean isCabinCompliant(String actual, String allowedSpec) {
        if (actual == null || actual.isBlank() || allowedSpec == null || allowedSpec.isBlank()) {
            return true; // 缺失数据时不拦截
        }
        List<String> allowedSet = splitCabinSpec(allowedSpec);
        int actualRank = rankOf(actual);

        for (String allowed : allowedSet) {
            int allowedRank = rankOf(allowed);
            if (actualRank >= 0 && allowedRank >= 0) {
                // 已知舱位：等级比较
                if (actualRank <= allowedRank) {
                    return true;
                }
            } else {
                // 未知舱位：回退字符串匹配
                String aLower = actual.toLowerCase();
                String allowedLower = allowed.toLowerCase();
                if (aLower.equals(allowedLower)
                        || allowedLower.contains(aLower)
                        || aLower.contains(allowedLower)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 将斜杠/逗号分隔的舱位字符串拆分为列表。
     */
    private static List<String> splitCabinSpec(String spec) {
        List<String> result = new ArrayList<>();
        for (String part : spec.replace("，", ",").replace("/", ",").split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
