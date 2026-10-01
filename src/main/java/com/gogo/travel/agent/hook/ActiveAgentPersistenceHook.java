package com.gogo.travel.agent.hook;

import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.context.SessionReactorContext;
import com.gogo.travel.agent.session.ActiveAgentSessionStore;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Objects;

public class ActiveAgentPersistenceHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(ActiveAgentPersistenceHook.class);

    private final ActiveAgentSessionStore activeAgentSessionStore;

    public ActiveAgentPersistenceHook(ActiveAgentSessionStore activeAgentSessionStore) {
        this.activeAgentSessionStore = activeAgentSessionStore;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreReasoningEvent)) {
            return Mono.just(event);
        }

        return Mono.deferContextual(ctx -> {

            AgentSessionContext sessionCtx = SessionReactorContext.from(ctx);

            if (sessionCtx == null) {
                logger.debug("[ActiveAgentPersistence]  未从 ReactorContext 中提取到会话上下文，跳过活跃 Agent 记录");
                return Mono.just(event);
            }
            String sessionId = sessionCtx.getSessionId();
            String agentName = event.getAgent() != null ? event.getAgent().getName() : null;

            if (!Objects.equals(agentName, "MasterAgent")) {
                activeAgentSessionStore.setActiveAgent(sessionId, agentName);
                logger.info("[ActiveAgentPersistence] session={} 活跃 Agent 已切换为 {}", sessionId, agentName);
            }
            return Mono.just(event);
        });
    }
}
