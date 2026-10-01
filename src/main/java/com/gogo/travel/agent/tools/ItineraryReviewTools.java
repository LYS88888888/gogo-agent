package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.SubjectiveAssessorAgent;
import com.gogo.travel.agent.common.PromptLoader;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.service.ReviewArbitratorService;
import com.gogo.travel.agent.session.ReviewResultSessionStore;
import com.gogo.travel.agent.tools.model.ReviewResult;
import com.gogo.travel.agent.tools.review.ObjectiveReviewEngine;
import com.gogo.travel.agent.tools.store.ItineraryPlanStore;
import com.gogo.travel.utils.JsonUtil;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.pipeline.Pipelines;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行程方案审核工具。
 * 提供客观六维结构化校验（review_planner_result）和多维审核编排（review_itinerary）。
 *
 */
@Component
public class ItineraryReviewTools {

    private static final Logger logger = LoggerFactory.getLogger(ItineraryReviewTools.class);

    // ============================== 常量 ==============================

    private static final double WEIGHT_PASS = 1.0;
    private static final double WEIGHT_WARNING = 0.7;
    private static final double WEIGHT_FAIL = 0.0;

    // ── 编排器常量 ──
    static final String SOURCE_OBJECTIVE = "objective";
    static final String SOURCE_EXPERIENCE = "experience";
    static final String SOURCE_RESILIENCE = "resilience";
    static final String SOURCE_PREFERENCE = "preference";
    static final String VERDICT_FAIL = "fail";
    static final String VERDICT_WARNING = "warning";

    private static final Map<String, String> DIMENSION_LABELS = Map.of(
            SOURCE_OBJECTIVE, "客观审核（六维确定性校验）",
            SOURCE_EXPERIENCE, "差旅体验审核",
            SOURCE_RESILIENCE, "行程韧性审核",
            SOURCE_PREFERENCE, "偏好匹配审核"
    );

    private static final List<String> SUBJECTIVE_SOURCES =
            List.of(SOURCE_EXPERIENCE, SOURCE_RESILIENCE, SOURCE_PREFERENCE);

    private static final String HARD_CONSTRAINT_PREFIX = "[硬约束]";

    // ── 依赖 ──
    private final ItineraryPlanStore itineraryPlanStore;
    private final Model stableModel;
    private final ReviewArbitratorService arbitrator;
    private final ReviewResultSessionStore reviewResultStore;
    private final String experiencePrompt;
    private final String resiliencePrompt;
    private final String preferencePrompt;
    private final ObjectiveReviewEngine objectiveReviewEngine;

    public ItineraryReviewTools(ItineraryPlanStore itineraryPlanStore,
                                @Qualifier("stableModel") Model stableModel,
                                ReviewArbitratorService arbitrator,
                                ReviewResultSessionStore reviewResultStore) {
        this.itineraryPlanStore = itineraryPlanStore;
        this.stableModel = stableModel;
        this.arbitrator = arbitrator;
        this.reviewResultStore = reviewResultStore;
        this.experiencePrompt = PromptLoader.load("review/trip-experience-assessor.md");
        this.resiliencePrompt = PromptLoader.load("review/resilience-assessor.md");
        this.preferencePrompt = PromptLoader.load("review/preference-assessor.md");
        this.objectiveReviewEngine = new ObjectiveReviewEngine();
    }

    @Tool(
            name = "review_itinerary",
            description = "对行程规划方案做多维审核（客观确定性校验 + 主观差旅体验/行程韧性/偏好匹配评估），仲裁生成整改单。" +
                    "规划结果由 plan_itinerary 自动存储，本工具自动读取，无需传入方案 JSON。" +
                    "上一轮审核结果自动加载（跨轮记忆），无需手动传入。"
    )
    public String reviewItinerary(
            @ToolParam(name = "origin", description = "出发地城市，如'上海'")
            String origin,
            @ToolParam(name = "destination", description = "目的地城市，如'杭州'")
            String destination,
            @ToolParam(name = "departure_date", description = "去程日期，格式 YYYY-MM-DD")
            String departureDate,
            @ToolParam(name = "policy",
                    description = "差旅政策 JSON 字符串，直接透传 query_travel_policy 的返回值。"
                            + "格式如：{\"userLevel\":\"L2\",\"destinationCity\":\"杭州\",\"cityTier\":\"NEW_FIRST_TIER\","
                            + "\"flightClass\":\"ECONOMY\",\"hotelLimit\":500.0,\"hotelStarLimit\":4,"
                            + "\"dailyMealLimit\":100.0,\"dailyTransportLimit\":80.0,\"trainSeatClass\":\"SECOND_CLASS\","
                            + "\"approvalThreshold\":3000.0,\"advanceBookingDays\":3}",
                    required = false) String policy,
            @ToolParam(name = "weather_summary",
                    description = "出发地与目的地的天气摘要文本，格式为「城市 天气状况 温度范围；城市 天气状况 温度范围」。"
                            + "如「上海 多云 28-35°C；杭州 小雨 26-33°C」。无天气数据时留空。用于行程韧性评估",
                    required = false) String weatherSummary,
            @ToolParam(name = "news_summary",
                    description = "出发地与目的地的资讯摘要文本，格式为「城市 资讯内容；城市 资讯内容」。"
                            + "涵盖交通管制、重大活动、突发事件等。如「上海无交通管制；杭州 7 月 15-17 日国际会展，部分道路限行」。无资讯数据时留空。用于行程韧性评估",
                    required = false) String newsSummary,
            @ToolParam(name = "user_preferences",
                    description = "用户差旅偏好自由文本，来自 retrieve_from_memory 的召回结果。"
                            + "内容为用户个人化的出行倾向描述，如「偏好早班直飞航班、出差只住华住会酒店、经济舱、避开红眼航班、需要可退改票」。"
                            + "无偏好数据时留空，工具将使用中性分评估",
                    required = false) String userPreferences,
            AgentSessionContext sessionCtx) {

        String userId = sessionCtx != null ? sessionCtx.getUserId() : null;
        String sessionId = sessionCtx != null ? sessionCtx.getSessionId() : null;
        logger.info("[REVIEW] 启动多维审核: userId={}, sessionId={}, hasPolicy={}, hasWeather={}, hasNews={}, hasPreferences={}",
                userId, sessionId, policy != null, weatherSummary != null, newsSummary != null, userPreferences != null);
        long startTime = System.currentTimeMillis();

        String previousReview = sessionId != null ? reviewResultStore.load(sessionId) : null;

        String itineraryPlan = loadItinerary(userId, origin, destination, departureDate);
        if (itineraryPlan == null) {
            return failReport("未找到规划结果。请确认 plan_itinerary 已成功执行。");
        }

        //通过编码方式实现6维的客观审核
        ReviewResult objectiveResult = safeObjectiveReview(policy, itineraryPlan);

        //通过LLM做三个维度的主观审核
        List<ReviewResult> subjectiveResults = runSubjectiveFanout(
                itineraryPlan, weatherSummary, newsSummary, userPreferences);

        //合并结果
        List<ReviewResult> allResults = mergeResults(objectiveResult, subjectiveResults);

        //判断是否硬否决
        boolean hardVetoed = VERDICT_FAIL.equals(objectiveResult.getVerdict());

        //仲裁
        ReviewResult arbitration = arbitrate(allResults, objectiveResult, hardVetoed, previousReview);

        //根据仲裁结果判断是否继续整改
        boolean continueRemediation = computeContinueRemediation(arbitration);

        //生成审核报告
        JSONObject report = buildReport(objectiveResult, subjectiveResults, arbitration, hardVetoed, continueRemediation);
        report.put("elapsed_ms", System.currentTimeMillis() - startTime);

        logger.info("[REVIEW] 审核完成: verdict={}, hardVetoed={}", arbitration.getVerdict(), hardVetoed);

        String reportJson = report.toJSONString();
        if (sessionId != null) {
            reviewResultStore.save(sessionId, reportJson);
        }
        return reportJson;
    }

    // ============================== 客观审核（基于已加载的规划 JSON） ==============================

    private String doObjectiveReviewFromContent(String content, String policy) {
        try {
            JSONObject resultJson = JSON.parseObject(content);
            JSONArray rawProposals = resultJson.getJSONArray("proposals");
            if (rawProposals == null || rawProposals.isEmpty()) {
                return failResponse("规划结果中未找到 proposals 方案数组");
            }

            JSONObject userRequest = resultJson.getJSONObject("user_request");
            JSONArray proposalsArr = new JSONArray();
            for (int i = 0; i < rawProposals.size(); i++) {
                proposalsArr.add(convertPlannerProposal(rawProposals.getJSONObject(i), userRequest, i));
            }

            JSONObject policyJson = policy != null ? JSON.parseObject(policy) : new JSONObject();
            return doReviewProposals(proposalsArr, policyJson);
        } catch (Exception e) {
            logger.error("[REVIEW] 解析规划结果失败: {}", e.getMessage());
            return failResponse("规划结果格式异常：" + e.getMessage());
        }
    }

    // ============================== 批量审核 ==============================

    private String doReviewProposals(JSONArray proposalsArr, JSONObject policyJson) {
        JSONArray reviewsArr = new JSONArray();
        String bestProposalId = null;
        int bestScore = -1;
        List<String> failIds = new ArrayList<>();
        List<String> warningIds = new ArrayList<>();
        List<String> passIds = new ArrayList<>();

        for (int i = 0; i < proposalsArr.size(); i++) {
            JSONObject proposal = proposalsArr.getJSONObject(i);
            String proposalId = proposal.getString("proposal_id");
            if (proposalId == null) {
                proposalId = "P" + (i + 1);
            }

            JSONObject itineraryView = proposal.getJSONObject("itinerary_view");
            if (itineraryView == null) {
                itineraryView = proposal;
            }

            JSONObject singleResult = objectiveReviewEngine.review(itineraryView, policyJson);
            singleResult.put("proposal_id", proposalId);
            putIfNotNull(singleResult, "proposal_label", proposal.getString("label"));

            JSONObject scoresObj = proposal.getJSONObject("scores");
            if (scoresObj != null) {
                singleResult.put("planner_scores", scoresObj);
            }
            reviewsArr.add(singleResult);

            // 分类统计
            String status = singleResult.getString("overall_status");
            switch (status) {
                case "pass" -> passIds.add(proposalId);
                case "warning" -> warningIds.add(proposalId);
                default -> failIds.add(proposalId);
            }

            // 选出 best
            int effectiveScore = computeEffectiveScore(status, scoresObj);
            if (effectiveScore > bestScore) {
                bestScore = effectiveScore;
                bestProposalId = proposalId;
            }
        }

        JSONObject result = new JSONObject();
        result.put("reviews", reviewsArr);
        result.put("best_proposal_id", bestProposalId);
        result.put("summary", new JSONObject(Map.of(
                "total", reviewsArr.size(),
                "pass_count", passIds.size(), "warning_count", warningIds.size(), "fail_count", failIds.size(),
                "pass_ids", passIds, "warning_ids", warningIds, "fail_ids", failIds)));
        result.put("overall_status", !failIds.isEmpty() ? "fail" : (!warningIds.isEmpty() ? "warning" : "pass"));
        result.put("issues", collectField(reviewsArr, "issues"));
        result.put("suggestions", collectField(reviewsArr, "suggestions"));

        logger.info("[TOOL][review] 批量审核完成: total={}, pass={}, warning={}, fail={}, best={}",
                reviewsArr.size(), passIds.size(), warningIds.size(), failIds.size(), bestProposalId);
        return result.toJSONString();
    }

    // ============================== 方案转换 ==============================

    private JSONObject convertPlannerProposal(JSONObject raw, JSONObject userRequest, int index) {
        JSONObject out = new JSONObject();

        JSONArray tags = raw.getJSONArray("tags");
        String label = (tags != null && !tags.isEmpty())
                ? String.join("/", tags.toJavaList(String.class)) : ("方案" + (index + 1));
        out.put("proposal_id", "P" + (index + 1));
        out.put("label", label);
        out.put("scores", raw.getJSONObject("scores"));
        out.put("itinerary_view", buildItineraryView(raw, userRequest));
        return out;
    }

    private JSONObject buildItineraryView(JSONObject raw, JSONObject userRequest) {
        JSONObject view = new JSONObject();
        String depDate = null;
        String retDate = null;

        if (userRequest != null) {
            view.put("origin", userRequest.getString("origin"));
            view.put("destination", userRequest.getString("destination"));
            depDate = userRequest.getString("departure_date");
            retDate = userRequest.getString("return_date");
            view.put("departure_date", depDate);
            view.put("return_date", retDate);
            view.put("approved_origin", userRequest.getString("origin"));
            view.put("approved_destination", userRequest.getString("destination"));
        }

        JSONObject metrics = raw.getJSONObject("metrics");
        if (metrics != null) {
            putIfNotNull(view, "total_budget", metrics.getDouble("total_price"));
            putIfNotNull(view, "total_transit_hhmm", metrics.getString("total_transit_hhmm"));
            putIfNotNull(view, "total_transit_min", metrics.getInteger("total_transit_min"));
        }

        JSONArray days = new JSONArray();
        // Day 1: 去程 + 住宿
        JSONObject day1 = new JSONObject();
        day1.put("date", depDate);
        putIfNotNull(day1, "outbound_transport", mapTransport(raw.getJSONObject("outbound")));
        putIfNotNull(day1, "hotel", mapHotel(raw.getJSONObject("hotel")));
        days.add(day1);
        // Day 2: 返程
        JSONObject day2 = new JSONObject();
        day2.put("date", retDate);
        putIfNotNull(day2, "return_transport", mapTransport(raw.getJSONObject("return")));
        days.add(day2);
        view.put("days", days);
        return view;
    }

    private JSONObject mapTransport(JSONObject t) {
        if (t == null) {
            return null;
        }
        JSONObject o = new JSONObject();
        o.put("type", t.getString("type"));
        o.put("carrier", t.getString("carrier"));
        o.put("code", t.getString("code"));
        putIfNotNull(o, "origin", t.getString("origin"));
        putIfNotNull(o, "destination", t.getString("destination"));
        o.put("departure", extractHm(t.getString("departure_time")));
        o.put("arrival", extractHm(t.getString("arrival_time")));
        putIfNotNull(o, "price", t.getDouble("price"));
        o.put("seat_class", t.getString("cabin_class"));
        putIfNotNull(o, "is_direct", t.getBoolean("is_direct"));
        return o;
    }

    private JSONObject mapHotel(JSONObject h) {
        if (h == null) {
            return null;
        }
        JSONObject o = new JSONObject();
        o.put("name", h.getString("name"));
        putIfNotNull(o, "room_type", h.getString("room_type"));
        putIfNotNull(o, "nights", h.getInteger("nights"));
        putIfNotNull(o, "price_per_night", h.getDouble("price_per_night"));
        putIfNotNull(o, "star_rating", h.getInteger("star_rating"));
        putIfNotNull(o, "brand", h.getString("brand"));
        putIfNotNull(o, "distance_to_main_venue_km", h.getDouble("distance_to_dest_km"));
        return o;
    }

    // ============================== 工具方法 ==============================

    private int computeEffectiveScore(String status, JSONObject plannerScores) {
        double weight = switch (status) {
            case "pass" -> WEIGHT_PASS;
            case "warning" -> WEIGHT_WARNING;
            default -> WEIGHT_FAIL;
        };
        double base = (plannerScores != null && plannerScores.getDouble("overall") != null)
                ? plannerScores.getDouble("overall") : 0.0;
        return (int) Math.round(base * weight);
    }

    /**
     * 从 reviews 数组中提取指定字段
     */
    private List<String> collectField(JSONArray reviews, String fieldName) {
        List<String> all = new ArrayList<>();
        for (int i = 0; i < reviews.size(); i++) {
            JSONObject r = reviews.getJSONObject(i);
            JSONArray items = r.getJSONArray(fieldName);
            if (items == null) {
                continue;
            }
            String pid = r.getString("proposal_id");
            for (int j = 0; j < items.size(); j++) {
                all.add("[" + pid + "] " + items.getString(j));
            }
        }
        return all;
    }

    /**
     * 从 ISO 时间字符串中提取 HH:mm
     */
    private String extractHm(String time) {
        if (time == null) {
            return null;
        }
        int t = time.indexOf('T');
        String hm = t >= 0 ? time.substring(t + 1) : time;
        return (hm.length() >= 5 && hm.charAt(2) == ':') ? hm.substring(0, 5) : hm;
    }

    private static void putIfNotNull(JSONObject target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String failResponse(String error) {
        return new JSONObject(Map.of(
                "overall_status", "fail",
                "error", error,
                "reviews", new JSONArray())).toJSONString();
    }

    private String loadItinerary(String userId, String origin, String destination, String departureDate) {
        try {
            return itineraryPlanStore.load(userId, origin, destination, departureDate);
        } catch (Exception e) {
            logger.error("[REVIEW] 读取规划结果失败: {}", e.getMessage());
            return null;
        }
    }

    private ReviewResult safeObjectiveReview(String policy, String rawJson) {
        try {
            String rawResponse = doObjectiveReviewFromContent(rawJson, policy);
            JSONObject parsed = JSON.parseObject(rawResponse);

            Map<String, Object> details = new HashMap<>();
            details.put("reviews", parsed.getJSONArray("reviews"));
            details.put("best_proposal_id", parsed.getString("best_proposal_id"));
            details.put("summary", parsed.getJSONObject("summary"));

            return ReviewResult.of(
                    SOURCE_OBJECTIVE,
                    parsed.getString("overall_status"),
                    jsonList(parsed.getJSONArray("issues")),
                    jsonList(parsed.getJSONArray("suggestions")),
                    details);
        } catch (Exception e) {
            logger.error("[REVIEW] 客观审核异常: {}", e.getMessage(), e);
            return fallbackResult(SOURCE_OBJECTIVE, e);
        }
    }

    private List<ReviewResult> runSubjectiveFanout(String itineraryPlan,
                                                   String weatherSummary,
                                                   String newsSummary,
                                                   String userPreferences) {
        String enrichedResiliencePrompt = enrichPrompt(resiliencePrompt, "当前天气摘要", weatherSummary);
        enrichedResiliencePrompt = enrichPrompt(enrichedResiliencePrompt, "当前资讯摘要", newsSummary);

        List<AgentBase> agents = List.of(
                new SubjectiveAssessorAgent("TripExperienceAssessor", SOURCE_EXPERIENCE,
                        experiencePrompt, stableModel),
                new SubjectiveAssessorAgent("ResilienceAssessor", SOURCE_RESILIENCE,
                        enrichedResiliencePrompt,
                        stableModel),
                new SubjectiveAssessorAgent("PreferenceAssessor", SOURCE_PREFERENCE,
                        buildPreferencePrompt(userPreferences),
                        stableModel)
        );

        Msg inputMsg = Msg.builder()
                .role(MsgRole.USER).name("user")
                .content(TextBlock.builder().text(formatItineraryJson(itineraryPlan)).build())
                .build();

        List<Msg> fanoutResults;
        try {
            fanoutResults = Pipelines.fanout(agents, inputMsg).block();
        } catch (Exception e) {
            logger.warn("[REVIEW] fanout 异常: {}", e.getMessage(), e);
            fanoutResults = List.of();
        }

        return parseAndFillGaps(fanoutResults);
    }

    private static String enrichPrompt(String basePrompt, String sectionTitle, String sectionContent) {
        if (sectionContent == null || sectionContent.isBlank()) {
            return basePrompt;
        }
        return basePrompt + "\n\n## " + sectionTitle + "\n" + sectionContent;
    }

    private String buildPreferencePrompt(String userPreferences) {
        if (userPreferences != null && !userPreferences.isBlank()) {
            return enrichPrompt(preferencePrompt, "当前用户偏好画像", userPreferences);
        }
        return preferencePrompt + "\n\n## 当前用户偏好画像\n暂无用户偏好数据，请仅基于方案标签与通用差旅常识进行评估。";
    }

    private List<ReviewResult> parseAndFillGaps(List<Msg> fanoutResults) {
        List<ReviewResult> results = new ArrayList<>();
        if (fanoutResults != null) {
            fanoutResults.forEach(msg -> results.add(parseMsg(msg)));
        }
        for (String src : SUBJECTIVE_SOURCES) {
            if (results.stream().noneMatch(r -> src.equals(r.getSource()))) {
                results.add(fallbackResult(src, null));
            }
        }
        return results;
    }

    private ReviewResult arbitrate(List<ReviewResult> allResults,
                                   ReviewResult objectiveResult,
                                   boolean hardVetoed,
                                   String previousReview) {
        if (!hardVetoed) {
            return arbitrator.arbitrate(allResults, previousReview);
        }

        logger.info("[REVIEW] 客观审核硬约束否决，进入强制整改流程");
        ReviewResult arbitration = arbitrator.arbitrate(allResults, previousReview);
        arbitration.setVerdict(VERDICT_FAIL);

        if (objectiveResult.getIssues() != null) {
            List<String> merged = new ArrayList<>(
                    arbitration.getIssues() != null ? arbitration.getIssues() : List.of());
            for (String issue : objectiveResult.getIssues()) {
                String tagged = HARD_CONSTRAINT_PREFIX + issue;
                if (!merged.contains(tagged) && !merged.contains(issue)) {
                    merged.add(tagged);
                }
            }
            arbitration.setIssues(merged);
        }
        return arbitration;
    }

    private JSONObject buildReport(ReviewResult objective,
                                   List<ReviewResult> subjective,
                                   ReviewResult arbitration,
                                   boolean hardVetoed,
                                   boolean continueRemediation) {
        JSONObject report = new JSONObject();
        report.put("verdict", arbitration.getVerdict());
        report.put("hard_vetoed", hardVetoed);
        report.put("continue_remediation", continueRemediation);

        JSONArray dimensions = new JSONArray();
        dimensions.add(dimensionEntry(SOURCE_OBJECTIVE, objective,
                hardVetoed ? "hard_fail" : objective.getVerdict()));
        subjective.forEach(r -> dimensions.add(dimensionEntry(r.getSource(), r, r.getVerdict())));
        report.put("dimensions", dimensions);

        if (objective.getDetails() != null) {
            report.put("best_proposal_id", objective.getDetails().get("best_proposal_id"));
        }

        report.put("arbitration", arbitration.getDetails());
        return report;
    }

    private static JSONObject dimensionEntry(String source, ReviewResult result, String verdict) {
        JSONObject entry = new JSONObject();
        entry.put("source", source);
        entry.put("label", DIMENSION_LABELS.getOrDefault(source, source));
        entry.put("verdict", verdict);
        entry.put("issues", nullSafe(result.getIssues()));
        entry.put("suggestions", nullSafe(result.getSuggestions()));
        return entry;
    }

    /**
     * 根据仲裁结果判断是否继续整改
     * @param arbitration
     * @return
     */
    @SuppressWarnings("unchecked")
    private static boolean computeContinueRemediation(ReviewResult arbitration) {
        if (VERDICT_FAIL.equals(arbitration.getVerdict())) {
            return true;
        }
        if (arbitration.getDetails() == null) {
            return false;
        }
        JSONObject details = new JSONObject(arbitration.getDetails());
        JSONArray remediation = details.getJSONArray("remediation_priority");
        if (remediation == null || remediation.isEmpty()) {
            return false;
        }
        String recommendedProposalId = details.getString("recommended_proposal_id");

        int fixableHighPriorityCount = 0;
        boolean recommendedHasFixableIssue = false;

        for (int i = 0; i < remediation.size(); i++) {
            JSONObject item = remediation.getJSONObject(i);
            if (item.getBooleanValue("hard_constraint", false)) {
                return true;
            }
            boolean fixable = item.getBooleanValue("fixable_by_replanning", true);
            int priority = item.getIntValue("priority", Integer.MAX_VALUE);
            if (fixable && priority <= 3) {
                fixableHighPriorityCount++;
                if (recommendedProposalId != null) {
                    JSONArray affected = item.getJSONArray("affected_proposals");
                    if (affected != null && affected.contains(recommendedProposalId)) {
                        recommendedHasFixableIssue = true;
                    }
                }
            }
        }
        return recommendedHasFixableIssue || fixableHighPriorityCount >= 2;
    }

    private static ReviewResult fallbackResult(String source, Throwable error) {
        return ReviewResult.of(
                source, VERDICT_WARNING,
                List.of(source + " 评估器执行异常" + (error != null ? "：" + error.getMessage() : "")),
                List.of("建议人工复核 " + source + " 相关维度"));
    }

    private ReviewResult parseMsg(Msg msg) {
        String source = msg.getName() != null ? msg.getName() : "unknown";
        String rawText = msg.getTextContent();
        try {
            String fixedJson = JsonUtil.fixJson(rawText);
            JSONObject json = JSON.parseObject(fixedJson);
            return ReviewResult.of(source,
                    json.getString("verdict"),
                    json.getList("issues", String.class),
                    json.getList("suggestions", String.class),
                    json.getJSONObject("details"));
        } catch (Exception e) {
            logger.warn("[REVIEW] 解析 {} 结果失败: {}", source, e.getMessage());
            return fallbackResult(source, e);
        }
    }

    private static String formatItineraryJson(String rawJson) {
        JSONObject obj = JSON.parseObject(rawJson);
        return "## 行程方案数据\n```json\n"
                + JSON.toJSONString(obj, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat)
                + "\n```\n";
    }

    private static List<ReviewResult> mergeResults(ReviewResult objective, List<ReviewResult> subjective) {
        List<ReviewResult> all = new ArrayList<>(1 + subjective.size());
        all.add(objective);
        all.addAll(subjective);
        return all;
    }

    private static List<String> jsonList(JSONArray arr) {
        return arr != null ? arr.toJavaList(String.class) : List.of();
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : List.of();
    }

    private static String failReport(String error) {
        JSONObject report = new JSONObject();
        report.put("verdict", VERDICT_FAIL);
        report.put("hard_vetoed", false);
        report.put("error", error);
        report.put("dimensions", new JSONArray());
        return report.toJSONString();
    }
}
