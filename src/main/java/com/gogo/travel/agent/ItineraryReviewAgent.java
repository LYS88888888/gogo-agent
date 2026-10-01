package com.gogo.travel.agent;

import com.gogo.travel.agent.common.PromptLoader;
import com.gogo.travel.agent.memory.AgentMemoryFactory;
import com.gogo.travel.agent.tools.ItineraryReviewTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.util.List;

/**
 * 行程方案审核子智能体。
 *
 * <p>由 ItineraryPlanAgent 在行程方案确认前委托调用，负责对规划好的行程进行六维结构化审核：
 * 行程完整性、时间合理性、出发地/目的地核实、预算合规性、交通舱位合规性、路径规划合理性、环境合理性（天气/交通资讯对方案的影响）（不含个人偏好，偏好由规划环节负责）。
 *
 * <p>调用工具：
 * <ul>
 *   <li>{@code review_planner_result}（ItineraryReviewTools）：按当前用户从Redis读取多方案并做结构化六维校验（不含环境维度，环境合理性由本 Agent 在第四步补充）</li>
 *   <li>{@code query_travel_policy / check_travel_policy}（PolicyTools）：政策查询与合规核验</li>
 *   <li>{@code query_weather / query_destination_news}（DestinationLiveTools）：出发地与目的地天气和资讯查询，用于环境合理性审核</li>
 * </ul>
 *
 */
@Configuration("itineraryReviewAgentConfiguration")
@Deprecated
public class ItineraryReviewAgent extends BaseSubAgent {

    /** 行程审核 Agent 最大推理迭代次数 */
    private static final int MAX_ITERATIONS = 8;

    @Autowired
    protected ItineraryReviewTools itineraryReviewTools;

    @Bean(name = "itineraryReviewAgent")
    @Scope("prototype")
    public ReActAgent build() {
        Toolkit toolkit = new Toolkit();
        // 政策查询与合规核验
        toolkit.registration().tool(policyTools).apply();
        // 天气与资讯查询（环境合理性审核所需）
        toolkit.registration().tool(destinationLiveTools).apply();
        // 行程结构化六维校验
        toolkit.registration().tool(itineraryReviewTools).apply();

        ReActAgent agent = ReActAgent.builder()
                .name("ItineraryReviewAgent")
                .description("行程方案审核专家，对规划好的行程进行七维结构化审核（完整性/时间/出发目的地/预算/舱位合规/路径/环境合理性），输出审核报告和具体修改建议")
                // 规划和审核使用不同模型
                .model(stableModel)
                .toolkit(toolkit)
                .toolExecutionContext(createToolCtx())
                // 自动压缩/卸载大工具结果与历史，抑制多轮输入 token 膨胀（需搭配 AutoContextHook）
                .memory(AgentMemoryFactory.create(stableModel))
                .longTermMemory(createLongTermMemory(memoryFactory))
                .longTermMemoryMode(LongTermMemoryMode.AGENT_CONTROL)
                .hooks(List.of(new AutoContextHook(), executionLoggerHook, progressNotifierHook))
                .sysPrompt(PromptLoader.load("itinerary-review-agent-system.md"))
                .toolExecutionConfig(getToolConfig())
                .maxIters(MAX_ITERATIONS)
                .build();

        return agent;
    }
}
