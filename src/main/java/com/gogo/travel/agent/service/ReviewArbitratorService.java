package com.gogo.travel.agent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.common.PromptLoader;
import com.gogo.travel.agent.tools.model.ReviewResult;
import com.gogo.travel.utils.JsonUtil;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审核仲裁器：汇总客观校验 + 主观评估结果，生成最终整改单。
 *
 */
@Component
public class ReviewArbitratorService {

    private static final Logger logger = LoggerFactory.getLogger(ReviewArbitratorService.class);

    private final Model model;
    private final String systemPrompt;

    public ReviewArbitratorService(@Qualifier("stableModel") Model model) {
        this.model = model;
        this.systemPrompt = PromptLoader.load("review/review-arbitrator.md");
    }

    /**
     * 对全部审核结果进行仲裁，生成最终整改单。
     *
     * @param allResults 所有处理器（客观 + 主观）的审核结果
     * @return 仲裁结果（结构化整改单）
     */
    public ReviewResult arbitrate(List<ReviewResult> allResults) {
        return arbitrate(allResults, null);
    }

    /**
     * 对全部审核结果进行仲裁，生成最终整改单。
     *
     * @param allResults     所有处理器（客观 + 主观）的审核结果
     * @param previousReview 上一轮审核结果 JSON，可为 null
     * @return 仲裁结果
     */
    public ReviewResult arbitrate(List<ReviewResult> allResults, String previousReview) {
        StringBuilder userMessage = new StringBuilder();
        userMessage.append("# 各维度审核结果汇总\n\n");

        for (ReviewResult r : allResults) {
            userMessage.append("## ").append(sourceLabel(r.getSource())).append("\n");
            userMessage.append("- 判定: ").append(r.getVerdict()).append("\n");

            if (r.getIssues() != null && !r.getIssues().isEmpty()) {
                userMessage.append("- 问题:\n");
                for (String issue : r.getIssues()) {
                    userMessage.append("  - ").append(issue).append("\n");
                }
            }

            if (r.getSuggestions() != null && !r.getSuggestions().isEmpty()) {
                userMessage.append("- 建议:\n");
                for (String s : r.getSuggestions()) {
                    userMessage.append("  - ").append(s).append("\n");
                }
            }

            if (r.getDetails() != null) {
                userMessage.append("- 详情: ").append(JSON.toJSONString(r.getDetails())).append("\n");
            }
            userMessage.append("\n");
        }

        if (previousReview != null && !previousReview.isBlank()) {
            userMessage.append("## 上一轮审核结果（修复前）\n");
            userMessage.append(previousReview).append("\n\n");
        }

        List<Msg> messages = List.of(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .name("system")
                        .content(TextBlock.builder().text(systemPrompt).build())
                        .build(),
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(TextBlock.builder().text(userMessage.toString()).build())
                        .build());

        try {
            List<ChatResponse> responses = model.stream(messages, null, null).collectList().block();
            String responseText = extractText(responses);
            logger.debug("[ARBITRATOR] LLM 仲裁响应长度: {}", responseText != null ? responseText.length() : 0);

            String fixedJson = JsonUtil.fixJson(responseText);
            JSONObject json = JSON.parseObject(fixedJson);
            ReviewResult result = new ReviewResult();
            result.setSource("arbitrator");
            result.setVerdict(json.getString("verdict"));
            result.setIssues(json.getList("issues", String.class));
            result.setSuggestions(json.getList("suggestions", String.class));
            result.setDetails(json.getJSONObject("details"));
            return result;
        } catch (Exception e) {
            logger.error("[ARBITRATOR] LLM 仲裁失败，降级为代码仲裁: {}", e.getMessage(), e);
            return fallbackArbitrate(allResults);
        }
    }

    /**
     * 降级仲裁。
     */
    private ReviewResult fallbackArbitrate(List<ReviewResult> results) {
        boolean anyHardFail = results.stream()
                .anyMatch(r -> "objective".equals(r.getSource()) && "fail".equals(r.getVerdict()));
        boolean anyWarning = results.stream().anyMatch(r -> "warning".equals(r.getVerdict()));

        ReviewResult result = new ReviewResult();
        result.setSource("arbitrator");

        if (anyHardFail) {
            result.setVerdict("fail");
        } else if (anyWarning) {
            result.setVerdict("warning");
        } else {
            result.setVerdict("pass");
        }

        List<String> allIssues = results.stream()
                .filter(r -> r.getIssues() != null)
                .flatMap(r -> r.getIssues().stream())
                .toList();
        result.setIssues(allIssues);

        List<String> allSuggestions = results.stream()
                .filter(r -> r.getSuggestions() != null)
                .flatMap(r -> r.getSuggestions().stream())
                .toList();
        result.setSuggestions(allSuggestions);

        return result;
    }

    private static String sourceLabel(String source) {
        return switch (source) {
            case "objective" -> "客观审核（六维确定性校验）";
            case "experience" -> "差旅体验审核";
            case "resilience" -> "行程韧性审核";
            case "preference" -> "偏好匹配审核";
            default -> source;
        };
    }

    private static String extractText(List<ChatResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatResponse response : responses) {
            List<ContentBlock> blocks = response.getContent();
            if (blocks == null) continue;
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock textBlock) {
                    sb.append(textBlock.getText());
                }
            }
        }
        return sb.toString();
    }
}
