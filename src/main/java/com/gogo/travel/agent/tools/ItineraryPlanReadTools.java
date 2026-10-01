package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.tools.planner.PlannerUtils;
import com.gogo.travel.agent.tools.planner.SearchResultConverter;
import com.gogo.travel.agent.tools.store.ItineraryPlanStore;
import com.gogo.travel.agent.tools.store.SearchCandidateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 查询和行程规划结果相关数据的工具
 *
 */
@Component
public class ItineraryPlanReadTools {

    private static final Logger logger = LoggerFactory.getLogger(ItineraryPlanReadTools.class);

    private final ItineraryPlanStore itineraryPlanStore;
    private final SearchResultConverter searchResultConverter;

    public ItineraryPlanReadTools(ItineraryPlanStore itineraryPlanStore,
                                  SearchCandidateStore searchCandidateStore) {
        this.itineraryPlanStore = itineraryPlanStore;
        this.searchResultConverter = new SearchResultConverter(searchCandidateStore);
    }

    // ============================== get_candidates: 查询搜索候选 ==============================

    @Tool(
            name = "get_candidates",
            description = "查询当前用户的搜索候选交通/酒店列表（含 id/type/时间/价格/舱位/品牌等）。" +
                    "审核修复阶段用此工具加载候选池，根据 remediation_priority 执行剔除/补搜/调整。"
    )
    public String getCandidates(
            @ToolParam(name = "origin", description = "出发地城市，如'上海'")
            String origin,
            @ToolParam(name = "destination", description = "目的地城市，如'杭州'")
            String destination,
            @ToolParam(name = "departure_date", description = "去程日期，格式 YYYY-MM-DD")
            String departureDate,
            @ToolParam(name = "return_date", description = "返程日期，格式 YYYY-MM-DD")
            String returnDate,

            AgentSessionContext sessionCtx) {

        String userId = sessionCtx != null ? sessionCtx.getUserId() : null;
        logger.info("[TOOL][get_candidates] userId={}, origin={}, destination={}, departureDate={}, returnDate={}",
                userId, origin, destination, departureDate, returnDate);

        JSONObject candidates = searchResultConverter.buildCandidates(userId, origin, destination, departureDate, returnDate);
        if (candidates != null) {
            return candidates.toJSONString();
        }
        JSONObject empty = new JSONObject();
        empty.put("transport_options", new JSONArray());
        empty.put("hotel_options", new JSONArray());
        logger.warn("[TOOL][get_candidates] Redis 中无搜索候选数据, userId={}", userId);
        return empty.toJSONString();
    }

    // ============================== get_proposals: 查询规划方案 ==============================

    @Tool(
            name = "get_proposals",
            description = "查询当前用户的规划代表方案与用户请求。" +
                    "返回 user_request（出发地/目的地/日期）、proposals（代表方案列表）、preferences（偏好文本）。" +
                    "审核修复时用此工具查看当前方案，根据 remediation_priority 执行调整。"
    )
    public String getProposals(
            @ToolParam(name = "origin", description = "出发地城市，如'上海'")
            String origin,
            @ToolParam(name = "destination", description = "目的地城市，如'杭州'")
            String destination,
            @ToolParam(name = "departure_date", description = "去程日期，格式 YYYY-MM-DD")
            String departureDate,

            AgentSessionContext sessionCtx) {
        String userId = sessionCtx != null ? sessionCtx.getUserId() : null;
        logger.info("[TOOL][get_proposals] userId={}, origin={}, destination={}, departureDate={}",
                userId, origin, destination, departureDate);

        String planJson = itineraryPlanStore.load(userId, origin, destination, departureDate);
        if (planJson == null) {
            logger.warn("[TOOL][get_proposals] Redis 中无规划结果, userId={}, tripKey={}",
                    userId, ItineraryPlanStore.tripKey(origin, destination, departureDate));
            return PlannerUtils.errorResult("Redis 中无规划结果，请先执行 plan_itinerary。");
        }

        try {
            JSONObject planResult = JSON.parseObject(planJson);
            JSONObject result = new JSONObject();
            result.put("user_request", planResult.getJSONObject("user_request"));
            result.put("proposals", planResult.getJSONArray("proposals"));
            result.put("preferences", planResult.getString("preferences"));
            return result.toJSONString();
        } catch (Exception e) {
            logger.warn("[TOOL][get_proposals] 解析规划结果失败: {}", e.getMessage());
            return PlannerUtils.errorResult("解析规划结果失败：" + e.getMessage());
        }
    }
}
