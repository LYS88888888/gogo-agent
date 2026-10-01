package com.gogo.travel.agent.tools.review;

import com.alibaba.fastjson2.JSONObject;

import java.util.List;

/**
 * 客观六维校验引擎：编排六个 {@link ObjectiveReviewer}，汇总校验结果。
 * <p>
 * 替代原 ItineraryReviewTools 中的 {@code reviewSingle()} 方法，
 * 将六维校验职责从工具类中解耦，便于独立单元测试和规则扩展。
 *
 */
public class ObjectiveReviewEngine {

    private final List<ObjectiveReviewer> reviewers;

    public ObjectiveReviewEngine() {
        this.reviewers = List.of(
                new CompletenessReviewer(),
                new TimingReviewer(),
                new OriginDestinationReviewer(),
                new BudgetReviewer(),
                new TransportReviewer(),
                new RouteReviewer()
        );
    }

    /**
     * 对单个行程方案执行六维客观校验。
     *
     * @param itinerary 行程方案 JSON
     * @param policy    差旅政策 JSON，可能为空对象
     * @return 校验结果 JSON，包含 overall_status / checks / issues / suggestions
     */
    public JSONObject review(JSONObject itinerary, JSONObject policy) {
        ReviewCollector collector = new ReviewCollector();
        for (ObjectiveReviewer reviewer : reviewers) {
            reviewer.review(itinerary, policy, collector);
        }

        String overallStatus = computeOverallStatus(collector.getChecks());

        JSONObject result = new JSONObject();
        result.put("overall_status", overallStatus);
        result.put("checks", collector.getChecks().stream().map(CheckResult::toJson).toList());
        result.put("issues", collector.getIssues());
        result.put("suggestions", collector.getSuggestions());
        return result;
    }

    private static String computeOverallStatus(List<CheckResult> checks) {
        boolean hasFail = checks.stream().anyMatch(c -> "fail".equals(c.status()));
        boolean hasWarning = checks.stream().anyMatch(c -> "warning".equals(c.status()));
        if (hasFail) {
            return "fail";
        }
        return hasWarning ? "warning" : "pass";
    }
}
