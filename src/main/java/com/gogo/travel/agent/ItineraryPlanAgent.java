package com.gogo.travel.agent;

import com.gogo.travel.agent.common.PromptLoader;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.context.AgentSessionContextHolder;
import com.gogo.travel.agent.hook.*;
import com.gogo.travel.agent.memory.AgentMemoryFactory;
import com.gogo.travel.agent.tools.*;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.PendingToolRecoveryHook;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.coding.ShellCommandTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static com.gogo.travel.agent.hook.ToolCircuitBreakerHook.TOOL_CIRCUIT_BREAKER_GROUP;

@Configuration("itineraryPlanAgentConfiguration")
public class ItineraryPlanAgent extends BaseSubAgent {

    /**
     * 行程规划 Agent 最大推理迭代次数
     */
    private static final int MAX_ITERATIONS = 30;

    @Autowired
    protected ApiKeyTools apiKeyTools;

    @Autowired
    protected SearchResultCaptureHook searchResultCaptureHook;

    @Autowired
    protected ItineraryReviewTools itineraryReviewTools;

    @Autowired
    protected PlanHtmlTools planHtmlTools;

    @Bean(name = "itineraryPlanAgent")
    @Scope("prototype")
    public ReActAgent build() {
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
                .parallel(true)
                .build());

        toolkit.createToolGroup(TOOL_CIRCUIT_BREAKER_GROUP, TOOL_CIRCUIT_BREAKER_GROUP, true);

        toolkit.registration().tool(policyTools).apply();
        toolkit.registration().tool(travelOrderReadTools).apply();
        toolkit.registration().tool(bookingReadTools).apply();
        toolkit.registration().tool(userInfoReadTools).apply();
        toolkit.registration().tool(userInfoWriteTools).apply();
        toolkit.registration().tool(apiKeyTools).apply();
        toolkit.registration().tool(destinationLiveTools).group(TOOL_CIRCUIT_BREAKER_GROUP).apply();
        toolkit.registration().tool(itineraryPlannerTools).apply();
        toolkit.registration().tool(itineraryPlanReadTools).apply();
        toolkit.registration().mcpClient(weatherMcpClient).enableTools(weatherMcpEnabledTools).apply();
        toolkit.registration().mcpClient(oriznVisaMcpClient).enableTools(oriznMcpEnabledTools).apply();

        toolkit.registration().tool(itineraryReviewTools).apply();
        toolkit.registration().tool(planHtmlTools).apply();

        SkillBox skillBox = buildPlanningSkillBox(toolkit);

        AgentSessionContext sessionCtx = AgentSessionContextHolder.get();
        String capturedSessionId = sessionCtx != null ? sessionCtx.getSessionId() : null;
        LongTermMemory longTermMemory = createLongTermMemory(memoryFactory);

        PlanNotebook planNotebook = PlanNotebook.builder()
                .needUserConfirm(false)
                .build();

        if (capturedSessionId != null) {
            planNotebook.addChangeHook("plan_progress_notifier",
                    (nb, plan) -> progressNotifierHook.sendPlanUpdate(capturedSessionId, plan));
        }

        AutoContextMemory memory = AgentMemoryFactory.create(stableModel);

        ReActAgent agent = ReActAgent.builder()
                .name("ItineraryPlanAgent")
                .description("智能行程规划专家，负责方案生成、比价分析、审核修复闭环")
                .model(strongModelWithThinking)
                .memory(memory)
                .longTermMemory(longTermMemory)
                .longTermMemoryMode(LongTermMemoryMode.AGENT_CONTROL)
                .toolkit(toolkit)
                .toolExecutionContext(createToolCtx())
                .skillBox(skillBox)
                .hooks(List.of(dynamicTimeInjectionHook, new AutoContextHook(), new PlanAwareThinkingHook(planNotebook, 2048), cliResultCompressHook, flightApiKeyHook, tuniuApiKeyHook, rghUserIsolationHook,
                        skillContentCollapseHook, sessionPersistenceHook, executionLoggerHook, toolCircuitBreakerHook,
                        progressNotifierHook, activeAgentPersistenceHook, searchResultCaptureHook,
                        new TokenUsageHook(), new PendingToolRecoveryHook()))
                .planNotebook(planNotebook)
                .toolExecutionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofMinutes(3))
                        .maxAttempts(TOOL_MAX_ATTEMPTS)
                        .initialBackoff(Duration.ofSeconds(TOOL_INITIAL_BACKOFF_SECONDS))
                        .retryOn(error -> error instanceof java.io.IOException)
                        .build())
                .sysPrompt(PromptLoader.loadStatic("itinerary-plan-agent-system.md"))
                .maxIters(MAX_ITERATIONS)
                .generateOptions(getEnableThinkingGenerateOptions(2048))
                .build();

        return agent;
    }

    private SkillBox buildPlanningSkillBox(Toolkit toolkit) {
        SkillBox skillBox = new SkillBox(toolkit);
        skillBox.registerSkill(skillRepository.getSkill("tuniu-cli"));
        skillBox.registerSkill(skillRepository.getSkill("html-plan"));
        ShellCommandTool shellTool = new ShellCommandTool(
                null,
                Set.of("curl", "npm", "npx", "tuniu", "rgh", "nohup", "sleep", "cat", "grep", "node", "date", "env", "which", "bash", "ls", "mkdir", "source", "echo"),
                null);
        skillBox.codeExecution().withShell(shellTool).withRead().withWrite().enable();
        return skillBox;
    }
}
