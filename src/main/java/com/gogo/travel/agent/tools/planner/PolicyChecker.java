package com.gogo.travel.agent.tools.planner;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * 政策软约束检查器：纯数值/枚举比较，命中即记 violation/warning，绝不剔除组合。
 *
 * <p>从 ItineraryPlannerTool 抽取，便于独立单元测试和规则扩展。
 *
 */
public class PolicyChecker {

    /** 政策评分基础分 */
    private static final double BASE_SCORE = 100.0;
    /** 每个 violation 扣减分值 */
    private static final double VIOLATION_PENALTY = 40.0;
    /** 每个 warning 扣减分值 */
    private static final double WARNING_PENALTY = 15.0;

    /** 政策检查结果 */
    public record PolicyResult(JSONArray violations, JSONArray warnings, double score) {
    }

    /**
     * 检查去程交通、酒店、返程交通的政策合规性。
     */
    public PolicyResult check(JSONObject ob, JSONObject hotel, JSONObject rb,
                              double totalPrice, JSONObject policy) {
        JSONArray violations = new JSONArray();
        JSONArray warnings = new JSONArray();

        Double hotelLimit = PlannerUtils.getDbl(policy, "hotelLimit");
        Double hotelPrice = PlannerUtils.getDbl(hotel, "price_per_night");
        if (hotelLimit != null && hotelPrice != null && hotelPrice > hotelLimit) {
            violations.add(String.format("酒店单晚 %s 超过限额 %s", PlannerUtils.fmtNum(hotelPrice), PlannerUtils.fmtNum(hotelLimit)));
        }

        // 酒店星级软约束
        Integer hotelStarLimit = PlannerUtils.getInt(policy, "hotelStarLimit");
        Integer starRating = PlannerUtils.getInt(hotel, "star_rating");
        if (hotelStarLimit != null && starRating != null && starRating > hotelStarLimit) {
            warnings.add(String.format("酒店星级 %d 超过政策上限 %d 星", starRating, hotelStarLimit));
        }

        Double approvalThreshold = PlannerUtils.getDbl(policy, "approvalThreshold");
        if (approvalThreshold != null && totalPrice > approvalThreshold) {
            violations.add(String.format("总价 %.0f 超过审批阈值 %s", totalPrice, PlannerUtils.fmtNum(approvalThreshold)));
        }

        // 舱位校验
        checkCabinPolicy(ob, "去程", policy, warnings);
        checkCabinPolicy(rb, "返程", policy, warnings);

        double score = BASE_SCORE
                       - VIOLATION_PENALTY * violations.size()
                       - WARNING_PENALTY * warnings.size();
        return new PolicyResult(violations, warnings, PlannerUtils.round2(Math.max(0.0, score)));
    }

    private void checkCabinPolicy(JSONObject leg, String label, JSONObject policy, JSONArray warnings) {
        String cabin = PlannerUtils.trimOrEmpty(PlannerUtils.getStr(leg, "cabin_class"));
        if (cabin.isEmpty()) {
            return;
        }
        String policyKey = "train".equals(PlannerUtils.getStr(leg, "type")) ? "trainSeatClass" : "flightClass";
        String allowed = PlannerUtils.getStr(policy, policyKey);
        if (allowed == null || allowed.isEmpty()) {
            return;
        }

        if (!com.gogo.travel.utils.CabinRankUtil.isCabinCompliant(cabin, allowed)) {
            warnings.add(String.format("%s舱位 '%s' 超出政策允许最高等级 %s", label, cabin, allowed));
        }
    }
}
