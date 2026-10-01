package com.gogo.travel.agent.tools.review;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 维度 1：行程完整性校验。
 * 检查出发地、目的地、去返程日期、每日交通/住宿/活动安排是否完整。
 *
 */
public class CompletenessReviewer implements ObjectiveReviewer {

    @Override
    public void review(JSONObject itinerary, JSONObject policy, ReviewCollector collector) {
        List<String> problems = new ArrayList<>();

        if (isBlank(itinerary.getString("origin"))) {
            problems.add("出发地（origin）未填写");
        }
        if (isBlank(itinerary.getString("destination"))) {
            problems.add("目的地（destination）未填写");
        }
        if (isBlank(itinerary.getString("departure_date"))) {
            problems.add("去程日期（departure_date）未填写");
        }
        if (isBlank(itinerary.getString("return_date"))) {
            problems.add("返程日期（return_date）未填写");
        }

        JSONArray days = itinerary.getJSONArray("days");
        if (days == null || days.isEmpty()) {
            problems.add("行程中无每日安排（days 为空）");
        } else {
            checkDaysCompleteness(days, problems, collector);
        }

        if (problems.isEmpty()) {
            collector.addCheck(new CheckResult("行程完整性", "pass", "去程/返程交通、住宿、活动安排均完整"));
        } else {
            collector.addIssues(problems);
            collector.addCheck(new CheckResult("行程完整性", "fail", "存在缺失项：" + String.join("；", problems)));
        }
    }

    private void checkDaysCompleteness(JSONArray days, List<String> problems, ReviewCollector collector) {
        boolean hasOutbound = false;
        boolean hasReturn = false;
        int nightsWithoutHotel = 0;
        int daysWithoutActivities = 0;

        for (int i = 0; i < days.size(); i++) {
            JSONObject day = days.getJSONObject(i);
            if (day.containsKey("outbound_transport")) {
                hasOutbound = true;
            }
            if (day.containsKey("return_transport")) {
                hasReturn = true;
            }
            JSONArray activities = day.getJSONArray("activities");
            if (activities == null || activities.isEmpty()) {
                daysWithoutActivities++;
            }
            // 最后一天不需要住宿（当天返回）
            if (i < days.size() - 1 && !day.containsKey("hotel")) {
                nightsWithoutHotel++;
            }
        }

        if (!hasOutbound) {
            problems.add("去程交通未安排");
        }
        if (!hasReturn) {
            problems.add("返程交通未安排");
        }
        if (nightsWithoutHotel > 0) {
            problems.add(nightsWithoutHotel + " 个夜晚未安排住宿");
        }
        if (daysWithoutActivities > 0) {
            collector.addSuggestion("有 " + daysWithoutActivities + " 天无具体活动安排，建议补充");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
