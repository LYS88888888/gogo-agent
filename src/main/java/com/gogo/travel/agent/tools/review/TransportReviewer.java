package com.gogo.travel.agent.tools.review;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.utils.CabinRankUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 维度 5：交通舱位/席别合规性校验。
 * 校验航班舱位、高铁席别是否超出差旅政策允许的最高等级。
 *
 */
public class TransportReviewer implements ObjectiveReviewer {

    @Override
    public void review(JSONObject itinerary, JSONObject policy, ReviewCollector collector) {
        if (policy.isEmpty()) {
            collector.addCheck(new CheckResult("交通舱位合规性", "warning", "未提供差旅政策，跳过舱位校验"));
            return;
        }

        String flightClass = policy.getString("flightClass");
        String trainSeatClass = policy.getString("trainSeatClass");
        if (flightClass == null && trainSeatClass == null) {
            collector.addCheck(new CheckResult("交通舱位合规性", "pass", "差旅政策未限定舱位/席别"));
            return;
        }

        List<String> violations = new ArrayList<>();
        JSONArray days = itinerary.getJSONArray("days");
        if (days != null) {
            for (int i = 0; i < days.size(); i++) {
                JSONObject day = days.getJSONObject(i);
                String dateStr = day.getString("date");
                JSONObject outbound = day.getJSONObject("outbound_transport");
                if (outbound != null) {
                    checkCabinClass(outbound, "去程", dateStr, flightClass, trainSeatClass, violations);
                }
                JSONObject returnT = day.getJSONObject("return_transport");
                if (returnT != null) {
                    checkCabinClass(returnT, "返程", dateStr, flightClass, trainSeatClass, violations);
                }
            }
        }

        if (violations.isEmpty()) {
            collector.addCheck(new CheckResult("交通舱位合规性", "pass", "交通舱位/席别符合差旅政策"));
        } else {
            collector.addIssues(violations);
            collector.addCheck(new CheckResult("交通舱位合规性", "warning", "存在舱位/席别超标：" + String.join("；", violations)));
            collector.addSuggestion("超标舱位/席别需降级至政策允许范围，或在方案说明中注明原因");
        }
    }

    private void checkCabinClass(JSONObject transport, String label, String dateStr,
                                 String flightClass, String trainSeatClass,
                                 List<String> violations) {
        String cabin = transport.getString("seat_class");
        if (cabin == null || cabin.isBlank()) {
            return;
        }
        String type = transport.getString("type");
        String allowed = "train".equals(type) ? trainSeatClass : flightClass;
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        if (!CabinRankUtil.isCabinCompliant(cabin, allowed)) {
            violations.add(dateStr + " " + label + "舱位/席别「" + cabin + "」超出政策允许最高等级「" + allowed + "」");
        }
    }
}
