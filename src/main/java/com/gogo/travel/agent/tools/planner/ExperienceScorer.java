package com.gogo.travel.agent.tools.planner;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出行体验评分器：红眼航班、到达过晚、末班交通、长通勤、恶劣天气等维度的体验惩罚计算。
 *
 * <p>从 ItineraryPlannerTool 抽取，便于独立单元测试和规则扩展。
 *
 */
public class ExperienceScorer {

    /** 红眼航班出发时间阈值（22:00） */
    private static final int RED_EYE_DEPARTURE_HOUR = 22;
    /** 红眼航班到达时间阈值（06:00） */
    private static final int RED_EYE_ARRIVAL_HOUR = 6;
    /** 到达时间过晚阈值（21:00），影响当天活动安排 */
    private static final int LATE_ARRIVAL_HOUR = 21;
    /** 长通勤阈值（分钟），4 小时 */
    private static final int LONG_COMMUTE_MINUTES = 240;
    /** 严重长通勤阈值（分钟），6 小时 */
    private static final int SEVERE_COMMUTE_MINUTES = 360;

    /** 体验扣分：红眼航班 */
    private static final double RED_EYE_PENALTY = 30.0;
    /** 体验扣分：到达时间过晚 */
    private static final double LATE_ARRIVAL_PENALTY = 15.0;
    /** 体验扣分：返程末班交通 */
    private static final double LAST_TRANSPORT_PENALTY = 10.0;
    /** 体验扣分：长通勤 4h+ */
    private static final double LONG_COMMUTE_PENALTY = 15.0;
    /** 体验扣分：严重长通勤 6h+ */
    private static final double SEVERE_COMMUTE_PENALTY = 25.0;
    /** 体验扣分：恶劣天气选飞机 */
    private static final double BAD_WEATHER_FLIGHT_PENALTY = 25.0;

    /** 恶劣天气关键词（匹配 weather_summary） */
    private static final List<String> BAD_WEATHER_KEYWORDS = List.of(
            "暴雨", "大雾", "大风", "雷暴", "暴雪", "雾霾", "台风", "冰雹");

    /**
     * 计算组合的出行体验评分。
     */
    public double computeScore(JSONObject ob, JSONObject rb, String weatherSummary) {
        double score = 100.0;

        // 1. 红眼航班惩罚
        if (isRedEye(ob)) {
            score -= RED_EYE_PENALTY;
        }
        if (isRedEye(rb)) {
            score -= RED_EYE_PENALTY;
        }

        // 2. 去程到达时间过晚（> 21:00）
        LocalDateTime obArr = PlannerUtils.parseDt(PlannerUtils.getStr(ob, "arrival_time"));
        if (obArr != null && obArr.getHour() >= LATE_ARRIVAL_HOUR) {
            score -= LATE_ARRIVAL_PENALTY;
        }

        // 3. 返程为当天末班交通（出发时间最晚的候选）——简化判断：返程出发 >= 21:00 且为飞机
        LocalDateTime rbDep = PlannerUtils.parseDt(PlannerUtils.getStr(rb, "departure_time"));
        if (rbDep != null && rbDep.getHour() >= LATE_ARRIVAL_HOUR && "flight".equals(PlannerUtils.getStr(rb, "type"))) {
            score -= LAST_TRANSPORT_PENALTY;
        }

        // 4. 单程通勤过长
        score = applyCommutePenalty(score, PlannerUtils.getInt(ob, "_transit_min"));
        score = applyCommutePenalty(score, PlannerUtils.getInt(rb, "_transit_min"));

        // 5. 恶劣天气选飞机
        if (hasSevereWeather(weatherSummary)) {
            if ("flight".equals(PlannerUtils.getStr(ob, "type"))) {
                score -= BAD_WEATHER_FLIGHT_PENALTY;
            }
            if ("flight".equals(PlannerUtils.getStr(rb, "type"))) {
                score -= BAD_WEATHER_FLIGHT_PENALTY;
            }
        }

        return PlannerUtils.round2(Math.max(0.0, score));
    }

    /**
     * 收集组合的体验风险标记。
     */
    public JSONArray collectFlags(JSONObject ob, JSONObject rb, String weatherSummary) {
        JSONArray flags = new JSONArray();
        if (isRedEye(ob)) {
            flags.add("去程为红眼航班");
        }
        if (isRedEye(rb)) {
            flags.add("返程为红眼航班");
        }
        LocalDateTime obArr = PlannerUtils.parseDt(PlannerUtils.getStr(ob, "arrival_time"));
        if (obArr != null && obArr.getHour() >= LATE_ARRIVAL_HOUR) {
            flags.add("去程到达时间过晚（" + obArr.getHour() + ":00 后）");
        }
        Integer obTransitMin = PlannerUtils.getInt(ob, "_transit_min");
        if (obTransitMin != null && obTransitMin >= LONG_COMMUTE_MINUTES) {
            flags.add("去程单程通勤" + PlannerUtils.formatDuration(obTransitMin));
        }
        Integer rbTransitMin = PlannerUtils.getInt(rb, "_transit_min");
        if (rbTransitMin != null && rbTransitMin >= LONG_COMMUTE_MINUTES) {
            flags.add("返程单程通勤" + PlannerUtils.formatDuration(rbTransitMin));
        }
        if (hasSevereWeather(weatherSummary)) {
            if ("flight".equals(PlannerUtils.getStr(ob, "type")) || "flight".equals(PlannerUtils.getStr(rb, "type"))) {
                flags.add("恶劣天气下选择飞机出行");
            }
        }
        return flags;
    }

    /**
     * 判断是否为红眼航班。
     */
    private static boolean isRedEye(JSONObject leg) {
        if (!"flight".equals(PlannerUtils.getStr(leg, "type"))) {
            return false;
        }
        LocalDateTime dep = PlannerUtils.parseDt(PlannerUtils.getStr(leg, "departure_time"));
        LocalDateTime arr = PlannerUtils.parseDt(PlannerUtils.getStr(leg, "arrival_time"));
        if (dep != null && dep.getHour() >= RED_EYE_DEPARTURE_HOUR) {
            return true;
        }
        return arr != null && arr.getHour() < RED_EYE_ARRIVAL_HOUR;
    }

    /**
     * 判断天气摘要是否包含恶劣天气关键词。
     */
    private static boolean hasSevereWeather(String weatherSummary) {
        if (weatherSummary == null || weatherSummary.isBlank()) {
            return false;
        }
        for (String keyword : BAD_WEATHER_KEYWORDS) {
            if (weatherSummary.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static double applyCommutePenalty(double score, Integer transitMin) {
        if (transitMin == null) {
            return score;
        }
        if (transitMin >= SEVERE_COMMUTE_MINUTES) {
            return score - SEVERE_COMMUTE_PENALTY;
        }
        if (transitMin >= LONG_COMMUTE_MINUTES) {
            return score - LONG_COMMUTE_PENALTY;
        }
        return score;
    }
}
