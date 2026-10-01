package com.gogo.travel.agent.tools.planner;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选方案排序与代表方案选择器。
 *
 * <p>职责：
 * <ul>
 *   <li>归一化 time/price/experience 分数</li>
 *   <li>合成偏好分和综合分</li>
 *   <li>选出 4 个代表方案（综合最佳/时间最短/价格最低/最符合偏好）</li>
 *   <li>构建最终结果 JSON</li>
 * </ul>
 *
 */
public class CandidateRanker {

    /** 默认维度权重：时间 */
    private static final double DEFAULT_WEIGHT_TIME = 0.20;
    /** 默认维度权重：价格 */
    private static final double DEFAULT_WEIGHT_PRICE = 0.10;
    /** 默认维度权重：偏好 */
    private static final double DEFAULT_WEIGHT_PREFERENCE = 0.40;
    /** 默认维度权重：出行体验 */
    private static final double DEFAULT_WEIGHT_EXPERIENCE = 0.30;

    /** 代表方案标签（有序） */
    private static final List<String> TAG_ORDER = List.of("综合最佳", "时间最短", "价格最低", "最符合偏好");

    /** legView 需要拷贝的字段 */
    private static final List<String> LEG_FIELDS = List.of(
            "id", "type", "carrier", "code", "departure_time", "arrival_time",
            "origin", "destination", "price", "cabin_class", "is_direct", "refund_policy");

    /** hotelView 需要拷贝的字段 */
    private static final List<String> HOTEL_FIELDS = List.of(
            "id", "name", "brand", "star_rating", "room_type", "price_per_night",
            "nights", "distance_to_dest_km", "breakfast_included", "cancel_policy");

    /** 单条候选偏好评分 */
    public record ScoreEntry(double score, JSONArray basis) {
        public static final ScoreEntry EMPTY = new ScoreEntry(0, new JSONArray());
    }

    // ============================== 归一化 ==============================

    /**
     * 对所有组合的 time/price/experience 原始值做 min-max 归一化。
     */
    public void normalizeScores(List<JSONObject> combos) {
        DoubleSummaryStatistics timeStats = combos.stream()
                .mapToDouble(c -> c.getDoubleValue("total_transit_min")).summaryStatistics();
        DoubleSummaryStatistics priceStats = combos.stream()
                .mapToDouble(c -> c.getDoubleValue("total_price")).summaryStatistics();
        DoubleSummaryStatistics experienceStats = combos.stream()
                .mapToDouble(c -> c.getDoubleValue("experience_score_raw")).summaryStatistics();

        for (JSONObject c : combos) {
            c.put("time_score", PlannerUtils.minmaxScore(c.getDouble("total_transit_min"),
                    timeStats.getMin(), timeStats.getMax(), false));
            c.put("price_score", PlannerUtils.minmaxScore(c.getDouble("total_price"),
                    priceStats.getMin(), priceStats.getMax(), false));
            c.put("experience_score", PlannerUtils.minmaxScore(c.getDouble("experience_score_raw"),
                    experienceStats.getMin(), experienceStats.getMax(), true));
        }
    }

    // ============================== 排序 + 选代表 ==============================

    /**
     * 计算每个 combo 的偏好分、综合分，选出 4 个代表方案，构建最终结果。
     */
    public JSONObject rankAndBuild(CalcContext ctx, JSONObject scores) {
        JSONObject tScores = PlannerUtils.nullSafe(scores.getJSONObject("transport_scores"));
        JSONObject hScores = PlannerUtils.nullSafe(scores.getJSONObject("hotel_scores"));
        double wTime = ctx.weights.getDoubleValue("time");
        double wPrice = ctx.weights.getDoubleValue("price");
        double wPref = ctx.weights.getDoubleValue("preference");
        double wExp = ctx.weights.getDoubleValue("experience");

        // 为每个 combo 计算偏好分、体验分和综合分
        for (JSONObject c : ctx.combos) {
            ScoreEntry obS = resolveScore(tScores, c.getString("outbound_id"));
            ScoreEntry hS = resolveScore(hScores, c.getString("hotel_id"));
            ScoreEntry rbS = resolveScore(tScores, c.getString("return_id"));
            double pref = PlannerUtils.round2((obS.score() + hS.score() + rbS.score()) / 3.0);
            c.put("preference_score", pref);
            c.put("preference_basis", mergeBasis(obS, hS, rbS));
            c.put("overall_score", PlannerUtils.round2(
                    c.getDoubleValue("time_score") * wTime
                    + c.getDoubleValue("price_score") * wPrice
                    + pref * wPref
                    + c.getDoubleValue("experience_score") * wExp));
        }

        // 选代表并合并同 combo 的 tag
        JSONArray proposals = buildProposals(ctx);

        // 综合最佳排前
        proposals.sort((a, b) -> Double.compare(
                ((JSONObject) b).getJSONObject("scores").getDoubleValue("overall"),
                ((JSONObject) a).getJSONObject("scores").getDoubleValue("overall")));

        JSONObject meta = new JSONObject();
        meta.put("generated_at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        meta.put("dimension_weights", ctx.weights);
        meta.put("combo_count", ctx.comboCount);

        JSONObject result = new JSONObject();
        result.put("meta", meta);
        result.put("user_request", ctx.userRequest);
        result.put("preferences", ctx.preferences);
        result.put("proposals", proposals);
        return result;
    }

    private JSONArray buildProposals(CalcContext ctx) {
        List<JSONObject> combos = ctx.combos;
        Map<String, JSONObject> picks = Map.of(
                "综合最佳", pickBest(combos, "overall_score", "preference_score", true),
                "时间最短", pickBestExperienceAware(combos, "total_transit_min", "total_price", false),
                "价格最低", pickBestExperienceAware(combos, "total_price", "total_transit_min", false),
                "最符合偏好", pickBest(combos, "preference_score", "overall_score", true));

        // 按 combo_id 去重，同一 combo 合并 tags
        Map<String, JSONObject> mergedCombo = new LinkedHashMap<>();
        Map<String, JSONArray> mergedTags = new LinkedHashMap<>();
        for (String tag : TAG_ORDER) {
            JSONObject c = picks.get(tag);
            String cid = c.getString("combo_id");
            mergedCombo.putIfAbsent(cid, c);
            mergedTags.computeIfAbsent(cid, k -> new JSONArray()).add(tag);
        }

        JSONArray proposals = new JSONArray();
        for (var entry : mergedCombo.entrySet()) {
            JSONObject c = entry.getValue();
            JSONObject p = new JSONObject();
            p.put("tags", mergedTags.get(entry.getKey()));
            p.put("outbound", ctx.legTransport.get(c.getString("outbound_id")));
            p.put("hotel", ctx.legHotels.get(c.getString("hotel_id")));
            p.put("return", ctx.legTransport.get(c.getString("return_id")));
            p.put("metrics", new JSONObject(Map.of(
                    "total_price", c.get("total_price"),
                    "total_transit_min", c.get("total_transit_min"),
                    "total_transit_hhmm", c.get("total_transit_hhmm"),
                    "stay_hours", c.get("stay_hours") != null ? c.get("stay_hours") : "")));
            p.put("scores", new JSONObject(Map.of(
                    "time", c.get("time_score"), "price", c.get("price_score"),
                    "preference", c.get("preference_score"), "policy", c.get("policy_score"),
                    "experience", c.get("experience_score"),
                    "overall", c.get("overall_score"))));
            p.put("preference_basis", c.get("preference_basis"));
            p.put("policy_violations", c.get("policy_violations"));
            p.put("warnings", c.get("warnings"));
            p.put("experience_flags", c.get("experience_flags"));
            proposals.add(p);
        }
        return proposals;
    }

    // ============================== 选最优方法 ==============================

    /**
     * 统一的"选最优"方法：higherIsBetter=true 取最大值，false 取最小值，key2 做平局打破。
     */
    private static JSONObject pickBest(List<JSONObject> combos, String key1, String key2, boolean higherIsBetter) {
        JSONObject best = null;
        for (JSONObject c : combos) {
            if (best == null) {
                best = c;
                continue;
            }
            int cmp = Double.compare(c.getDoubleValue(key1), best.getDoubleValue(key1));
            if (cmp == 0) {
                cmp = Double.compare(c.getDoubleValue(key2), best.getDoubleValue(key2));
            }
            if (higherIsBetter ? cmp > 0 : cmp < 0) {
                best = c;
            }
        }
        return best;
    }

    /**
     * 体验感知的"选最优"方法：避免选中有体验风险（红眼/长通勤等）的方案。
     */
    private static JSONObject pickBestExperienceAware(List<JSONObject> combos, String key1, String key2, boolean higherIsBetter) {
        JSONObject best = pickBest(combos, key1, key2, higherIsBetter);
        if (best == null) {
            return null;
        }

        JSONArray bestFlags = best.getJSONArray("experience_flags");
        if (bestFlags == null || bestFlags.isEmpty()) {
            return best;
        }

        double bestVal = best.getDoubleValue(key1);
        double tolerance = higherIsBetter ? bestVal * 0.85 : bestVal * 1.15;

        JSONObject cleanBest = null;
        for (JSONObject c : combos) {
            JSONArray flags = c.getJSONArray("experience_flags");
            if (flags != null && !flags.isEmpty()) {
                continue;
            }
            double val = c.getDoubleValue(key1);
            boolean withinTolerance = higherIsBetter ? val >= tolerance : val <= tolerance;
            if (!withinTolerance) {
                continue;
            }
            if (cleanBest == null) {
                cleanBest = c;
                continue;
            }
            int cmp = Double.compare(c.getDoubleValue(key1), cleanBest.getDoubleValue(key1));
            if (cmp == 0) {
                cmp = Double.compare(c.getDoubleValue(key2), cleanBest.getDoubleValue(key2));
            }
            if (higherIsBetter ? cmp > 0 : cmp < 0) {
                cleanBest = c;
            }
        }

        return cleanBest != null ? cleanBest : best;
    }

    // ============================== 偏好分解析 ==============================

    private static ScoreEntry resolveScore(JSONObject scoreMap, String legId) {
        Object v = scoreMap.get(legId);
        if (v == null) {
            return ScoreEntry.EMPTY;
        }
        if (v instanceof Number num) {
            return new ScoreEntry(num.doubleValue(), new JSONArray());
        }
        JSONObject o = scoreMap.getJSONObject(legId);
        double score = PlannerUtils.getDblOr(o, "score", 0);
        JSONArray basis = o.getJSONArray("basis");
        return new ScoreEntry(score, basis != null ? basis : new JSONArray());
    }

    private static JSONArray mergeBasis(ScoreEntry obS, ScoreEntry hS, ScoreEntry rbS) {
        JSONArray result = new JSONArray();
        String[] prefixes = {"去程", "住宿", "返程"};
        ScoreEntry[] entries = {obS, hS, rbS};
        for (int i = 0; i < entries.length; i++) {
            JSONArray basis = entries[i].basis();
            for (int j = 0; j < basis.size(); j++) {
                result.add(prefixes[i] + ": " + basis.getString(j));
            }
        }
        return result;
    }

    // ============================== 权重归一化 ==============================

    public static JSONObject normalizeWeights(JSONObject weights) {
        double time = PlannerUtils.getDblOr(weights, "time", DEFAULT_WEIGHT_TIME);
        double price = PlannerUtils.getDblOr(weights, "price", DEFAULT_WEIGHT_PRICE);
        double pref = PlannerUtils.getDblOr(weights, "preference", DEFAULT_WEIGHT_PREFERENCE);
        double exp = PlannerUtils.getDblOr(weights, "experience", DEFAULT_WEIGHT_EXPERIENCE);
        double sum = time + price + pref + exp;
        if (sum <= 0) {
            return new JSONObject(Map.of(
                    "time", DEFAULT_WEIGHT_TIME, "price", DEFAULT_WEIGHT_PRICE,
                    "preference", DEFAULT_WEIGHT_PREFERENCE, "experience", DEFAULT_WEIGHT_EXPERIENCE));
        }
        return new JSONObject(Map.of(
                "time", PlannerUtils.round2(time / sum), "price", PlannerUtils.round2(price / sum),
                "preference", PlannerUtils.round2(pref / sum), "experience", PlannerUtils.round2(exp / sum)));
    }

    // ============================== 视图构建 ==============================

    public static JSONObject toLegView(JSONObject t) {
        JSONObject view = PlannerUtils.copyFields(t, LEG_FIELDS);
        Integer transit = PlannerUtils.getInt(t, "_transit_min");
        view.put("transit_min", transit);
        view.put("transit_hhmm", PlannerUtils.formatDuration(transit));
        return view;
    }

    public static JSONObject toHotelView(JSONObject h) {
        return PlannerUtils.copyFields(h, HOTEL_FIELDS);
    }
}
