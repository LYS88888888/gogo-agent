package com.gogo.travel.agent.tools.review;

import java.util.ArrayList;
import java.util.List;

/**
 * 校验结果收集器（Collecting Parameter 模式）。
 * <p>
 * 各 {@link ObjectiveReviewer} 在校验过程中将 CheckResult、issues、suggestions
 * 写入此收集器，由 {@link ObjectiveReviewEngine} 统一汇总。
 *
 */
public class ReviewCollector {

    private final List<CheckResult> checks = new ArrayList<>();
    private final List<String> issues = new ArrayList<>();
    private final List<String> suggestions = new ArrayList<>();

    public void addCheck(CheckResult check) {
        checks.add(check);
    }

    public void addIssue(String issue) {
        issues.add(issue);
    }

    public void addIssues(List<String> newIssues) {
        issues.addAll(newIssues);
    }

    public void addSuggestion(String suggestion) {
        suggestions.add(suggestion);
    }

    public void addSuggestions(List<String> newSuggestions) {
        suggestions.addAll(newSuggestions);
    }

    public List<CheckResult> getChecks() {
        return checks;
    }

    public List<String> getIssues() {
        return issues;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }
}
