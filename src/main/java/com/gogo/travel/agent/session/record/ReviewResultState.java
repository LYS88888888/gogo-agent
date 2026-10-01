package com.gogo.travel.agent.session.record;

import io.agentscope.core.state.State;

/**
 * 行程审核结果的持久化状态。
 *
 * @param reviewResultJson 审核报告的完整 JSON 字符串
 */
public record ReviewResultState(String reviewResultJson) implements State {
}
