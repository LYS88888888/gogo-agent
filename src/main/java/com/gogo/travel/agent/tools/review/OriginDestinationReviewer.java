package com.gogo.travel.agent.tools.review;

import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 维度 3：出发地与目的地核实。
 * 校验行程出发地/目的地与审批单是否一致。
 *
 */
public class OriginDestinationReviewer implements ObjectiveReviewer {

    @Override
    public void review(JSONObject itinerary, JSONObject policy, ReviewCollector collector) {
        String origin = itinerary.getString("origin");
        String destination = itinerary.getString("destination");
        String approvedOrigin = itinerary.getString("approved_origin");
        String approvedDestination = itinerary.getString("approved_destination");

        List<String> problems = new ArrayList<>();
        if (!ObjectiveReviewer.cityMatches(origin, approvedOrigin)) {
            problems.add("出发地「" + origin + "」与审批单中「" + approvedOrigin + "」不一致");
        }
        if (!ObjectiveReviewer.cityMatches(destination, approvedDestination)) {
            problems.add("目的地「" + destination + "」与审批单中「" + approvedDestination + "」不一致");
        }

        if (problems.isEmpty()) {
            collector.addCheck(new CheckResult("出发地与目的地核实", "pass",
                    "出发地「" + origin + "」→ 目的地「" + destination + "」与审批单一致"));
        } else {
            collector.addIssues(problems);
            collector.addCheck(new CheckResult("出发地与目的地核实", "fail", String.join("；", problems)));
        }
    }
}
