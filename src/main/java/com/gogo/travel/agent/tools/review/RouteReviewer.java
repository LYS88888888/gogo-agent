package com.gogo.travel.agent.tools.review;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 维度 6：路径规划合理性校验。
 * 校验交通出发地/目的地与行程一致性，以及酒店距主要活动场所距离。
 *
 */
public class RouteReviewer implements ObjectiveReviewer {

    private static final double MAX_HOTEL_DISTANCE_KM = 15.0;

    @Override
    public void review(JSONObject itinerary, JSONObject policy, ReviewCollector collector) {
        JSONArray days = itinerary.getJSONArray("days");
        if (days == null || days.isEmpty()) {
            collector.addCheck(new CheckResult("路径规划合理性", "pass", "无每日行程，无需路径规划校验"));
            return;
        }

        List<String> warnings = new ArrayList<>();
        checkRouteConsistency(itinerary, days, warnings);
        checkHotelDistance(days, warnings);

        if (warnings.isEmpty()) {
            collector.addCheck(new CheckResult("路径规划合理性", "pass", "交通路线与住宿位置均合理"));
        } else {
            collector.addCheck(new CheckResult("路径规划合理性", "warning", String.join("；", warnings)));
            collector.addSuggestions(warnings);
        }
    }

    private void checkRouteConsistency(JSONObject itinerary, JSONArray days, List<String> warnings) {
        String origin = itinerary.getString("origin");
        String destination = itinerary.getString("destination");

        for (int i = 0; i < days.size(); i++) {
            JSONObject day = days.getJSONObject(i);
            String dateStr = day.getString("date");
            JSONObject outbound = day.getJSONObject("outbound_transport");
            if (outbound != null) {
                String obOrigin = outbound.getString("origin");
                String obDest = outbound.getString("destination");
                if (obOrigin != null && !ObjectiveReviewer.cityMatches(obOrigin, origin)) {
                    warnings.add(dateStr + " 去程出发地「" + obOrigin + "」与行程出发地「" + origin + "」不一致");
                }
                if (obDest != null && !ObjectiveReviewer.cityMatches(obDest, destination)) {
                    warnings.add(dateStr + " 去程目的地「" + obDest + "」与行程目的地「" + destination + "」不一致");
                }
            }
            JSONObject returnT = day.getJSONObject("return_transport");
            if (returnT != null) {
                String rtOrigin = returnT.getString("origin");
                String rtDest = returnT.getString("destination");
                if (rtOrigin != null && !ObjectiveReviewer.cityMatches(rtOrigin, destination)) {
                    warnings.add(dateStr + " 返程出发地「" + rtOrigin + "」与行程目的地「" + destination + "」不一致");
                }
                if (rtDest != null && !ObjectiveReviewer.cityMatches(rtDest, origin)) {
                    warnings.add(dateStr + " 返程目的地「" + rtDest + "」与行程出发地「" + origin + "」不一致");
                }
            }
        }
    }

    private void checkHotelDistance(JSONArray days, List<String> warnings) {
        for (int i = 0; i < days.size(); i++) {
            JSONObject day = days.getJSONObject(i);
            JSONObject hotel = day.getJSONObject("hotel");
            if (hotel == null) {
                continue;
            }
            Double distance = hotel.getDouble("distance_to_main_venue_km");
            if (distance != null && distance > MAX_HOTEL_DISTANCE_KM) {
                String dateStr = day.getString("date");
                warnings.add(dateStr + " 酒店距主要活动场所约 " + String.format("%.1f", distance)
                             + " km，建议选择更近的住宿");
            }
        }
    }
}
