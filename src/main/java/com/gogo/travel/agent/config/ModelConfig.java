package com.gogo.travel.agent.config;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ModelConfig {

    @Value("${agentscope.dashscope.api-key:${DASHSCOPE_API_KEY:}}")
    private String apiKey;

    @Bean("fastModel")
    public Model fastModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                // qwen3.8-flash：百炼免费额度 1M token
                .modelName("deepseek-v4-flash-0731")
                .stream(true)
                .enableThinking(false)
                .build();
    }

    @Bean("strongModel")
    @Primary
    public Model strongModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                // kimi-k3：百炼免费额度 1M token
                .modelName("deepseek-v4-flash-0731")
                .stream(true)
                .enableThinking(false)
                .build();
    }

    @Bean("strongModelWithThinking")
    public Model strongModelWithThinking() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                // qwen3.8-27b：百炼免费额度 1M token，qwen3 系列支持深度思考
                .modelName("deepseek-v4-flash-0731")
                .stream(true)
                // 开启深度思考
                .enableThinking(true)
                .build();
    }

    @Bean("stableModel")
    public Model stableModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                // qwen3.7-flash-2026-07-15：百炼免费额度 1M token，快照版本输出稳定
                .modelName("deepseek-v4-flash-0731")
                .stream(true)
                .enableThinking(false)
                .build();
    }
}
