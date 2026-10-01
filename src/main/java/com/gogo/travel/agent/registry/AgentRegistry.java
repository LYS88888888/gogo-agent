package com.gogo.travel.agent.registry;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Agent 注册表。
 *
 * <p>由于所有 Agent Bean 都是 prototype scope，每次 {@link #getAgent} 调用都通过
 * {@link ApplicationContext#getBean} 获取全新实例，保证不同 session 之间内存完全隔离。</p>
 *
 * <p><b>集群注意：</b>不再维护运行时白名单。prototype bean 的 build 方法在首次被调用时
 * 才会执行 register，若某节点从未实例化过目标 Agent，依赖白名单会导致跨节点恢复失败。
 * 这里直接按 PascalCase agentName 转首字母小写得到 Spring beanName，从容器获取实例。</p>
 *
 * <p>本项目存在两类 Agent Bean：由 {@code @Configuration + @Bean} 构建的 {@link ReActAgent}
 * （MasterAgent 及各子智能体），以及直接继承 {@link io.agentscope.core.agent.AgentBase} 的
 * {@code IntentRecognitionAgent} / {@code QueryRewritingAgent}。二者的共同父类型是
 * {@link Agent}，因此 {@link #getAgent(String)} 按 {@link Agent} 取 bean 以同时兼容两类；
 * 需要 ReActAgent 专属能力（memory / 工具续跑）时用 {@link #getAgent(String, Class)}
 * 显式指定类型。</p>
 *
 */
@Component
public class AgentRegistry {

    private static final Logger logger = LoggerFactory.getLogger(AgentRegistry.class);

    @Autowired
    private ApplicationContext context;

    /**
     * 获取一个全新的 agent 实例（prototype scope），兼容 {@link ReActAgent} 与
     * {@code IntentRecognitionAgent} / {@code QueryRewritingAgent} 这类直接继承
     * {@link io.agentscope.core.agent.AgentBase} 的 Agent。
     *
     * <p>agentName 命名规则：首字母小写即为 Spring bean 名，例如 "ItineraryManageAgent" → "itineraryManageAgent"。</p>
     */
    public Agent getAgent(String name) {
        return getAgent(name, Agent.class);
    }

    /**
     * 按指定类型获取一个全新的 agent 实例（prototype scope）。
     *
     * <p><b>集群注意：</b>直接按约定 beanName 从 Spring 容器获取，由 bean definition 本身保证合法性，
     * 避免某节点未实例化过该 Agent 时白名单校验导致恢复失败。</p>
     *
     * @param name 首字母大写的 Agent 名，如 "MasterAgent"
     * @param type 期望的 Agent 类型；类型不匹配时返回 {@code null}
     */
    public <T extends Agent> T getAgent(String name, Class<T> type) {
        String beanName = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        try {
            return context.getBean(beanName, type);
        } catch (Exception e) {
            logger.error("[AgentRegistry] 获取 Agent bean 失败: name={}, beanName={}, type={}",
                    name, beanName, type.getSimpleName(), e);
            return null;
        }
    }
}
