package com.gogo.travel.agent.tools.model;

import java.util.List;
import java.util.Map;

/**
 * 单个审核处理器（客观校验器或主观评估器）的结构化输出。
 */
public class ReviewResult {

    /** 处理器标识：objective / experience / resilience / preference / arbitrator */
    private String source;

    /** 综合判定：pass / warning / fail */
    private String verdict;

    /** 发现的问题列表（自然语言） */
    private List<String> issues;

    /** 改进建议列表（自然语言，必须是可执行的具体动作） */
    private List<String> suggestions;

    /** 处理器特有的详细信息（各评估器自由填充） */
    private Map<String, Object> details;

    public ReviewResult() {
    }

    public ReviewResult(String source, String verdict, List<String> issues, List<String> suggestions) {
        this.source = source;
        this.verdict = verdict;
        this.issues = issues;
        this.suggestions = suggestions;
    }

    public ReviewResult(String source, String verdict, List<String> issues, List<String> suggestions,
                         Map<String, Object> details) {
        this.source = source;
        this.verdict = verdict;
        this.issues = issues;
        this.suggestions = suggestions;
        this.details = details;
    }

    /**
     * 快捷工厂方法：无 details 的 ReviewResult。
     */
    public static ReviewResult of(String source, String verdict,
                                   List<String> issues, List<String> suggestions) {
        return new ReviewResult(source, verdict, issues, suggestions);
    }

    /**
     * 快捷工厂方法：带 details 的 ReviewResult。
     */
    public static ReviewResult of(String source, String verdict,
                                   List<String> issues, List<String> suggestions,
                                   Map<String, Object> details) {
        return new ReviewResult(source, verdict, issues, suggestions, details);
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public List<String> getIssues() {
        return issues;
    }

    public void setIssues(List<String> issues) {
        this.issues = issues;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }
}
