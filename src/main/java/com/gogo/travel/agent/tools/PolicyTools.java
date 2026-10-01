package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.business.policy.entity.PolicyCheckResult;
import com.gogo.travel.business.policy.entity.TravelPolicy;
import com.gogo.travel.business.policy.service.TravelPolicyService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 差旅政策工具集：查询政策标准 + 合规校验，共享 TravelPolicyService。
 *
 *
 */
@Component
public class PolicyTools {

    private static final Logger logger = LoggerFactory.getLogger(PolicyTools.class);

    @Autowired
    private TravelPolicyService travelPolicyService;

    @Tool(name = "query_travel_policy",
            description = "查询指定用户和目的城市的差旅政策标准（餐标、酒店标准、交通补贴等）")
    public String queryTravelPolicy(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "city", description = "目的城市") String city) {
        return resolvePolicyJson(sessionCtx, city, "query_travel_policy");
    }

    @Tool(name = "check_travel_policy",
            description = "校验行程或订单是否符合差旅政策，返回合规结果和超标说明")
    public String checkTravelPolicy(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "city", description = "目的城市") String city,
            @ToolParam(name = "order_summary",
                    description = "订单摘要JSON，例如 {\"type\":\"HOTEL\",\"amount\":450}") String orderSummary) {

        logger.info("[TOOL][check_travel_policy] city={}, orderSummary={}", city, orderSummary);
        TravelPolicy policy = JSON.parseObject(
                resolvePolicyJson(sessionCtx, city, "check_travel_policy"), TravelPolicy.class);
        PolicyCheckResult result = travelPolicyService.checkCompliance(orderSummary, policy);
        String json = JSON.toJSONString(result);
        logger.info("[TOOL][check_travel_policy] done");
        return json;
    }

    /**
     * 获取指定城市的差旅政策 JSON：优先读会话级缓存，miss 时查 service 并回填缓存，
     * 供查询 / 合规校验两个工具共用，避免同一会话内重复查库。
     */
    private String resolvePolicyJson(AgentSessionContext sessionCtx, String city, String toolName) {
        String cached = sessionCtx.getTravelPolicy(city);
        if (cached != null) {
            logger.info("[TOOL][{}] hit cache, city={}", toolName, city);
            return cached;
        }
        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][{}] miss cache, userId={}, city={}", toolName, userId, city);
        TravelPolicy policy = travelPolicyService.getPolicy(userId, city);
        String result = JSON.toJSONString(policy);
        logger.info("[TOOL][{}] result={}", toolName, result);
        sessionCtx.setTravelPolicy(city, result);
        return result;
    }
}
