package com.gogo.travel.agent.tools.review;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 维度 4：预算合规性校验。
 * 校验酒店单价、星级是否超出政策上限，总价是否超出审批阈值。
 *
 */
public class BudgetReviewer implements ObjectiveReviewer {

    @Override
    public void review(JSONObject itinerary, JSONObject policy, ReviewCollector collector) {
        if (policy.isEmpty()) {
            collector.addCheck(new CheckResult("预算合规性", "warning", "未提供差旅政策，跳过预算校验"));
            collector.addSuggestion("建议调用 query_travel_policy 获取差旅政策后再做预算校验");
            return;
        }

        Double hotelLimit = policy.getDouble("hotelLimit");
        Integer hotelStarLimit = policy.getInteger("hotelStarLimit");
        Double approvalThreshold = policy.getDouble("approvalThreshold");
        List<String> overBudget = new ArrayList<>();
        double totalCost = 0;

        JSONArray days = itinerary.getJSONArray("days");
        if (days != null) {
            totalCost = checkDaysBudget(days, hotelLimit, hotelStarLimit, overBudget);
        }

        // 优先使用行程自带的 total_budget 字段
        Double total = itinerary.getDouble("total_budget");
        double itineraryTotal = total != null ? total : totalCost;
        if (approvalThreshold != null && itineraryTotal > approvalThreshold) {
            overBudget.add("总价 ¥" + String.format("%.0f", itineraryTotal)
                           + " 超出审批阈值 ¥" + String.format("%.0f", approvalThreshold) + "，需额外审批");
        }

        if (overBudget.isEmpty()) {
            collector.addCheck(new CheckResult("预算合规性", "pass",
                    "预算合规，预计总费用 ¥" + String.format("%.0f", itineraryTotal)));
        } else {
            collector.addIssues(overBudget);
            collector.addCheck(new CheckResult("预算合规性", "warning", "存在超预算项：" + String.join("；", overBudget)));
            collector.addSuggestion("超预算项目需在方案说明中注明原因，或调整为更经济的选项");
        }
    }

    private double checkDaysBudget(JSONArray days, Double hotelLimit, Integer hotelStarLimit,
                                   List<String> overBudget) {
        double totalCost = 0;
        for (int i = 0; i < days.size(); i++) {
            JSONObject day = days.getJSONObject(i);
            String dateStr = day.getString("date");

            JSONObject hotel = day.getJSONObject("hotel");
            if (hotel != null) {
                double pricePerNight = hotel.getDoubleValue("price_per_night");
                totalCost += pricePerNight;
                if (hotelLimit != null && pricePerNight > hotelLimit) {
                    overBudget.add(dateStr + " 住宿价格 ¥" + pricePerNight + " 超出政策上限 ¥" + hotelLimit);
                }
                Integer starRating = hotel.getInteger("star_rating");
                if (hotelStarLimit != null && starRating != null && starRating > hotelStarLimit) {
                    overBudget.add(dateStr + " 酒店星级 " + starRating + " 超出政策上限 " + hotelStarLimit + " 星");
                }
            }

            JSONObject outbound = day.getJSONObject("outbound_transport");
            if (outbound != null) {
                totalCost += outbound.getDoubleValue("price");
            }
            JSONObject returnT = day.getJSONObject("return_transport");
            if (returnT != null) {
                totalCost += returnT.getDoubleValue("price");
            }
        }
        return totalCost;
    }
}
