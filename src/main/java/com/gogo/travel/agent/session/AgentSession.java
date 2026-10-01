package com.gogo.travel.agent.session;

import io.agentscope.core.ReActAgent;

public class AgentSession {

    private final String sessionId;
    private final ReActAgent agent;
    private final String pendingToolUseId;

    public AgentSession(String sessionId, ReActAgent agent, String pendingToolUseId) {
        this.sessionId = sessionId;
        this.agent = agent;
        this.pendingToolUseId = pendingToolUseId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public ReActAgent getAgent() {
        return agent;
    }

    public String getPendingToolUseId() {
        return pendingToolUseId;
    }
}
