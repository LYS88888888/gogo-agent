package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.tools.planner.CalcContext;
import com.gogo.travel.agent.tools.planner.CandidateRanker;
import com.gogo.travel.agent.tools.planner.ExperienceScorer;
import com.gogo.travel.agent.tools.planner.PolicyChecker;
import com.gogo.travel.agent.tools.planner.PlannerUtils;
import com.gogo.travel.agent.tools.planner.SearchResultConverter;
import com.gogo.travel.agent.tools.store.ItineraryPlanStore;
import com.gogo.travel.agent.tools.store.SearchCandidateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 往返行程规划工具（去程 + 住宿 + 返程）的客观计算引擎。
 *
 * <p>设计原则：
 * <ul>
 *   <li>本工具只做"客观数学"：时间差、价格求和、min-max 归一化、policy 硬性数值比较、组合过滤、排序。</li>
 *   <li>本工具绝不做任何"偏好判断"。偏好分完全由 LLM 事先产出（scores 入参），
 *       工具只负责按 (去+住+返)/3 合成。</li>
 * </ul>
 *
 * <p>具体的六维计算逻辑已抽取到 {@code planner} 包：
 * <ul>
 *   <li>{@link ExperienceScorer} — 出行体验评分（红眼/晚到达/长通勤/恶劣天气）</li>
 *   <li>{@link PolicyChecker} — 政策软约束检查</li>
 *   <li>{@link SearchResultConverter} — 搜索结果→候选数据转换</li>
 *   <li>{@link CandidateRanker} — 排序与代表方案选择</li>
 *   <li>{@link PlannerUtils} — 通用静态工具方法</li>
 * </ul>
 *
 */
@Component
public class ItineraryPlannerTools {

    private static final Logger logger = LoggerFactory.getLogger(ItineraryPlannerTools.class);

    private final ItineraryPlanStore itineraryPlanStore;
    private final SearchResultConverter searchResultConverter;
    private final ExperienceScorer experienceScorer = new ExperienceScorer();
    private final PolicyChecker policyChecker = new PolicyChecker();
    private final CandidateRanker candidateRanker = new CandidateRanker();

    public ItineraryPlannerTools(ItineraryPlanStore itineraryPlanStore,
                                 SearchCandidateStore searchCandidateStore) {
        this.itineraryPlanStore = itineraryPlanStore;
        this.searchResultConverter = new SearchResultConverter(searchCandidateStore);
    }

    // ============================== @Tool 入口 ==============================

    @Tool(
            name = "plan_itinerary",
            description = "往返行程规划客观计算引擎：自动读取搜索阶段捕获的候选交通/酒店 + 你（LLM）预先产出的偏好分，" +
                          "计算并选出代表方案，将结果自动存储（按当前用户隔离，无需也无法指定存储位置）并返回摘要。" +
                          "本工具只做客观数学，绝不解读偏好文本；偏好判断必须由你在 scores 参数里给出。"
    )
    public String planItinerary(
            @ToolParam(name = "origin", description = "出发地城市，如'上海'")
            String origin,
            @ToolParam(name = "destination", description = "目的地城市，如'杭州'")
            String destination,
            @ToolParam(name = "departure_date", description = "去程日期，格式 YYYY-MM-DD")
            String departureDate,
            @ToolParam(name = "return_date", description = "返程日期，格式 YYYY-MM-DD")
            String returnDate,

            @ToolParam(name = "preferences",
                    description = "描述用户偏好的自由文本（来自 retrieve_from_memory），无则留空。仅供打分参考，本工具不解读。",
                    required = false)
            String preferences,

            @ToolParam(name = "scores",
                    description = "LLM事先对每个候选打的偏好分 JSON。结构固定：" +
                                  "{ transport_scores:{ \"T1\":{score:0-100, basis:[中文理由]}, ... }, " +
                                  "hotel_scores:{ \"H1\":{score:0-100, basis:[中文理由]}, ... } }。" +
                                  "key 必须与候选 id 完全一致，未打分的 id 按 0 分处理。" +
                                  "留空或传 \"auto\" 时，工具自动为所有候选生成中性分(50)。",
                    required = false)
            String scores,

            @ToolParam(name = "policy",
                    description = "差旅政策 JSON（来自 query_travel_policy 透传）：{hotelLimit,hotelStarLimit,flightClass,trainSeatClass,approvalThreshold,advanceBookingDays}，无则留空。",
                    required = false)
            String policy,

            @ToolParam(name = "weather_summary",
                    description = "天气摘要（Plan Agent 已查询的天气信息合并文本），用于出行体验评分中的恶劣天气惩罚。" +
                                  "格式示例：'上海 多云 28-35°C；杭州 小雨 26-33°C'。包含暴雨/大雾/大风/雷暴等关键词时，" +
                                  "飞机会被扣分，优先推荐高铁。无则留空。",
                    required = false)
            String weatherSummary,

            AgentSessionContext sessionCtx) {

        String userId = sessionCtx != null ? sessionCtx.getUserId() : null;
        logger.info("[TOOL][plan_itinerary] 开始规划，结果存入 Redis Key: {}", ItineraryPlanStore.keyOf(userId));

        JSONObject data;
        JSONObject scoreObj;
        try {
            JSONObject candidatesJson = searchResultConverter.buildCandidates(userId, origin, destination, departureDate, returnDate);
            if (candidatesJson == null) {
                return PlannerUtils.errorResult("Redis 中无搜索结果。请先执行搜索命令（机票/火车票/酒店搜索）后再调用。");
            }
            data = buildInputData(origin, destination, departureDate, returnDate,
                    preferences, candidatesJson.toJSONString(), policy, weatherSummary);

            if (scores == null || scores.isBlank() || "auto".equalsIgnoreCase(scores)) {
                scoreObj = generateNeutralScores(data.getJSONObject("candidates"));
                logger.info("[TOOL][plan_itinerary] 使用自动中性分(50)进行打分");
            } else {
                scoreObj = PlannerUtils.parseJsonOrEmpty(scores);
            }
        } catch (Exception e) {
            logger.error("[TOOL][plan_itinerary] 入参 JSON 解析失败: {}", e.getMessage());
            return PlannerUtils.errorResult("scores / policy 不是合法 JSON：" + e.getMessage());
        }

        try {
            CalcContext ctx = buildCalcContext(data);
            if (ctx.error != null) {
                logger.warn("[TOOL][plan_itinerary] 规划失败: {}", ctx.error);
                return PlannerUtils.errorResult(ctx.error);
            }
            JSONObject result = candidateRanker.rankAndBuild(ctx, scoreObj);
            String resultJson = JSON.toJSONString(result, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
            itineraryPlanStore.save(userId, origin, destination, departureDate, resultJson);

            return buildSummary(result, ctx, userId);
        } catch (Exception e) {
            logger.error("[TOOL][plan_itinerary] 规划异常", e);
            return PlannerUtils.errorResult("规划过程发生异常：" + e.getMessage());
        }
    }

    // ============================== 入参组装 ==============================

    private JSONObject buildInputData(String origin, String destination, String departureDate,
                                      String returnDate, String preferences, String candidates,
                                      String policy, String weatherSummary) {
        JSONObject data = new JSONObject();
        data.put("user_request", new JSONObject(Map.of(
                "origin", origin, "destination", destination,
                "departure_date", departureDate, "return_date", returnDate)));
        data.put("preferences", PlannerUtils.nullToEmpty(preferences));
        data.put("weather_summary", PlannerUtils.nullToEmpty(weatherSummary));
        data.put("dimension_weights", new JSONObject(Map.of(
                "time", 0.20, "price", 0.10,
                "preference", 0.40, "experience", 0.30)));
        data.put("candidates", PlannerUtils.parseJsonOrEmpty(candidates));
        data.put("policy", PlannerUtils.parseJsonOrEmpty(policy));
        return data;
    }

    private String buildSummary(JSONObject result, CalcContext ctx, String userId) {
        JSONArray proposals = result.getJSONArray("proposals");
        JSONObject tags = new JSONObject();
        for (int i = 0; i < proposals.size(); i++) {
            JSONObject p = proposals.getJSONObject(i);
            JSONArray tg = p.getJSONArray("tags");
            String firstTag = (tg != null && !tg.isEmpty()) ? tg.getString(0) : "";
            tags.put(firstTag, p.getJSONObject("scores").get("overall"));
        }
        logger.info("[TOOL][plan_itinerary] 完成，combo={}, proposals={}", ctx.comboCount, proposals.size());
        return new JSONObject(Map.of(
                "ok", true,
                "redis_key", ItineraryPlanStore.keyOf(userId),
                "combo_count", ctx.comboCount,
                "proposal_count", proposals.size(),
                "tags", tags)).toJSONString();
    }

    // ============================== 计算上下文构建 ==============================

    private CalcContext buildCalcContext(JSONObject data) {
        CalcContext ctx = new CalcContext();
        JSONObject userRequest = PlannerUtils.nullSafe(data.getJSONObject("user_request"));
        JSONObject policy = PlannerUtils.nullSafe(data.getJSONObject("policy"));
        JSONObject candidates = PlannerUtils.nullSafe(data.getJSONObject("candidates"));

        JSONArray transports = PlannerUtils.nullSafeArr(candidates.getJSONArray("transport_options"));
        JSONArray hotels = PlannerUtils.nullSafeArr(candidates.getJSONArray("hotel_options"));

        if (transports.isEmpty() || hotels.isEmpty()) {
            ctx.error = "输入缺少 transport_options 或 hotel_options，无法规划。";
            return ctx;
        }

        // 1) 切分去/返程
        List<JSONObject> outbound = new ArrayList<>();
        List<JSONObject> inbound = new ArrayList<>();
        splitTransport(transports, userRequest, outbound, inbound);

        if (outbound.isEmpty() || inbound.isEmpty()) {
            ctx.error = String.format("去程或返程为空（去程 %d 条 / 返程 %d 条），请检查 transport_options 的 direction/时间字段。",
                    outbound.size(), inbound.size());
            return ctx;
        }

        List<JSONObject> hotelList = IntStream.range(0, hotels.size())
                .mapToObj(hotels::getJSONObject).toList();

        // 2) 笛卡尔积 + 过滤
        String weatherSummary = PlannerUtils.nullToEmpty(data.getString("weather_summary"));
        List<JSONObject> combos = buildCombinations(outbound, hotelList, inbound, policy, weatherSummary);
        if (combos.isEmpty()) {
            ctx.error = "所有组合都被过滤，可能是返程时间都早于去程到达。";
            return ctx;
        }

        // 3) 归一化时间/价格/体验分
        candidateRanker.normalizeScores(combos);

        // 4) 构建单项索引
        outbound.forEach(t -> ctx.legTransport.putIfAbsent(PlannerUtils.getStr(t, "id"), CandidateRanker.toLegView(t)));
        inbound.forEach(t -> ctx.legTransport.putIfAbsent(PlannerUtils.getStr(t, "id"), CandidateRanker.toLegView(t)));
        hotelList.forEach(h -> ctx.legHotels.putIfAbsent(PlannerUtils.getStr(h, "id"), CandidateRanker.toHotelView(h)));

        ctx.userRequest = userRequest;
        ctx.preferences = PlannerUtils.nullToEmpty(data.getString("preferences"));
        ctx.weatherSummary = PlannerUtils.nullToEmpty(data.getString("weather_summary"));
        ctx.weights = CandidateRanker.normalizeWeights(PlannerUtils.nullSafe(data.getJSONObject("dimension_weights")));
        ctx.combos = combos;
        ctx.comboCount = combos.size();
        return ctx;
    }

    // ============================== 切分去/返程 ==============================

    private void splitTransport(JSONArray transports, JSONObject userRequest,
                                List<JSONObject> outbound, List<JSONObject> inbound) {
        for (int i = 0; i < transports.size(); i++) {
            JSONObject t = new JSONObject(transports.getJSONObject(i));
            t.put("_transit_min", PlannerUtils.durationMinutes(PlannerUtils.getStr(t, "departure_time"), PlannerUtils.getStr(t, "arrival_time")));
            if ("outbound".equals(classifyDirection(t, userRequest))) {
                outbound.add(t);
            } else {
                inbound.add(t);
            }
        }
    }

    /**
     * 判断一条 transport 属于去程还是返程。
     * 优先级：显式 direction &gt; 出发城市匹配 &gt; 出发日期匹配。
     */
    private static String classifyDirection(JSONObject t, JSONObject userRequest) {
        String direction = PlannerUtils.trimOrEmpty(PlannerUtils.getStr(t, "direction")).toLowerCase();

        if (Set.of("outbound", "去程", "go", "depart").contains(direction)) {
            return "outbound";
        }
        if (Set.of("return", "返程", "back", "inbound").contains(direction)) {
            return "return";
        }

        // 按出发城市匹配
        String originCity = PlannerUtils.trimOrEmpty(PlannerUtils.getStr(userRequest, "origin"));
        String tOrigin = PlannerUtils.trimOrEmpty(PlannerUtils.getStr(t, "origin"));
        if (!originCity.isEmpty() && !tOrigin.isEmpty()) {
            if (cityMatches(tOrigin, originCity)) {
                return "outbound";
            }
            String destCity = PlannerUtils.trimOrEmpty(PlannerUtils.getStr(userRequest, "destination"));
            if (!destCity.isEmpty() && cityMatches(tOrigin, destCity)) {
                return "return";
            }
        }

        // 按日期匹配
        LocalDateTime depDate = PlannerUtils.parseDt(PlannerUtils.getStr(t, "departure_time"));
        LocalDateTime retDate = PlannerUtils.parseDt(PlannerUtils.trimOrEmpty(PlannerUtils.getStr(userRequest, "return_date")) + "T00:00");
        if (depDate != null && retDate != null && !depDate.toLocalDate().isBefore(retDate.toLocalDate())) {
            return "return";
        }
        LocalDateTime depReq = PlannerUtils.parseDt(PlannerUtils.trimOrEmpty(PlannerUtils.getStr(userRequest, "departure_date")) + "T00:00");
        if (depDate != null && depReq != null && !depDate.toLocalDate().isAfter(depReq.toLocalDate())) {
            return "outbound";
        }
        return "outbound";
    }

    /** 城市名模糊匹配：任一以另一开头（至少比较 2 字符） */
    private static boolean cityMatches(String city1, String city2) {
        String prefix1 = city1.length() >= 2 ? city1.substring(0, 2) : city1;
        return city1.startsWith(city2) || city2.startsWith(prefix1);
    }

    // ============================== 笛卡尔积 + 过滤 ==============================

    private List<JSONObject> buildCombinations(List<JSONObject> outbound, List<JSONObject> hotelList,
                                               List<JSONObject> inbound, JSONObject policy, String weatherSummary) {
        logger.debug("buildCombinations: outbound={}, hotelList={}, inbound={}", outbound.size(), hotelList.size(), inbound.size());
        List<JSONObject> combos = new ArrayList<>();
        for (JSONObject ob : outbound) {
            for (JSONObject h : hotelList) {
                for (JSONObject rb : inbound) {
                    JSONObject combo = tryBuildCombo(ob, h, rb, policy, weatherSummary);
                    if (combo != null) {
                        combos.add(combo);
                    }
                }
            }
        }
        return combos;
    }

    private JSONObject tryBuildCombo(JSONObject ob, JSONObject h, JSONObject rb, JSONObject policy, String weatherSummary) {
        LocalDateTime obArr = PlannerUtils.parseDt(PlannerUtils.getStr(ob, "arrival_time"));
        LocalDateTime rbDep = PlannerUtils.parseDt(PlannerUtils.getStr(rb, "departure_time"));
        // 返程出发必须晚于去程到达
        if (obArr != null && rbDep != null && !rbDep.isAfter(obArr)) {
            return null;
        }

        Integer obTransit = PlannerUtils.getInt(ob, "_transit_min");
        Integer rbTransit = PlannerUtils.getInt(rb, "_transit_min");
        int totalTransit = PlannerUtils.orZero(obTransit) + PlannerUtils.orZero(rbTransit);
        int nights = Math.max(1, PlannerUtils.getIntOr(h, "nights", 1));
        double hotelTotal = PlannerUtils.getDblOr(h, "price_per_night", 0) * nights;
        double totalPrice = PlannerUtils.getDblOr(ob, "price", 0) + PlannerUtils.getDblOr(rb, "price", 0) + hotelTotal;
        Double stayHours = (obArr != null && rbDep != null)
                ? PlannerUtils.round1((rbDep.toEpochSecond(ZoneOffset.UTC) - obArr.toEpochSecond(ZoneOffset.UTC)) / 3600.0)
                : null;

        PolicyChecker.PolicyResult pr = policyChecker.check(ob, h, rb, totalPrice, policy);
        double experienceScore = experienceScorer.computeScore(ob, rb, weatherSummary);
        JSONArray experienceFlags = experienceScorer.collectFlags(ob, rb, weatherSummary);

        JSONObject combo = new JSONObject();
        combo.put("combo_id", PlannerUtils.getStr(ob, "id") + "|" + PlannerUtils.getStr(h, "id") + "|" + PlannerUtils.getStr(rb, "id"));
        combo.put("outbound_id", PlannerUtils.getStr(ob, "id"));
        combo.put("hotel_id", PlannerUtils.getStr(h, "id"));
        combo.put("return_id", PlannerUtils.getStr(rb, "id"));
        combo.put("total_price", PlannerUtils.round2(totalPrice));
        combo.put("outbound_transit_min", obTransit);
        combo.put("return_transit_min", rbTransit);
        combo.put("total_transit_min", totalTransit);
        combo.put("total_transit_hhmm", PlannerUtils.formatDuration(totalTransit));
        combo.put("stay_hours", stayHours);
        combo.put("policy_score", pr.score());
        combo.put("policy_violations", pr.violations());
        combo.put("warnings", pr.warnings());
        combo.put("experience_score_raw", experienceScore);
        combo.put("experience_flags", experienceFlags);
        return combo;
    }

    // ============================== 中性分生成 ==============================

    /**
     * 为 candidates 中的所有交通和酒店候选生成中性分（50）。
     * 适用于用户无偏好或快速初稿场景。
     */
    private static JSONObject generateNeutralScores(JSONObject candidates) {
        JSONObject scores = new JSONObject();
        JSONObject tScores = new JSONObject();
        JSONObject hScores = new JSONObject();
        JSONArray basis = new JSONArray();
        basis.add("无偏好设置，使用中性分");

        JSONArray transports = PlannerUtils.nullSafeArr(candidates.getJSONArray("transport_options"));
        for (int i = 0; i < transports.size(); i++) {
            JSONObject t = transports.getJSONObject(i);
            String id = t.getString("id");
            JSONObject s = new JSONObject();
            s.put("score", 50);
            s.put("basis", basis);
            tScores.put(id, s);
        }

        JSONArray hotels = PlannerUtils.nullSafeArr(candidates.getJSONArray("hotel_options"));
        for (int i = 0; i < hotels.size(); i++) {
            JSONObject h = hotels.getJSONObject(i);
            String id = h.getString("id");
            JSONObject s = new JSONObject();
            s.put("score", 50);
            s.put("basis", basis);
            hScores.put(id, s);
        }

        scores.put("transport_scores", tScores);
        scores.put("hotel_scores", hScores);
        return scores;
    }

}
