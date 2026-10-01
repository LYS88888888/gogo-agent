package com.gogo.travel.agent.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 用量统计 Hook：逐轮累加 LLM 调用的真实 token 用量，Agent 调用结束时输出一次汇总。
 *
 * <p>每轮 reasoning 的用量由框架写入消息 metadata（{@link Msg#getChatUsage()}）。
 * 注意 {@code agent.call()} 返回的最终消息只携带最后一轮的用量，全程统计必须逐轮累加。</p>
 *
 * <p>本 Hook 需随 Agent 实例创建（{@code new TokenUsageHook()}，不注册为单例 Bean），
 * 累计值即该 Agent 实例整个生命周期的总消耗——含 ask_user 暂停后续跑的轮次
 * （暂停时 {@link PostCallEvent} 也会触发一次，输出的是截至当时的阶段性累计）。</p>
 *
 */
public class TokenUsageHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(TokenUsageHook.class);

    private final AtomicLong inputTokens = new AtomicLong();

    private final AtomicLong outputTokens = new AtomicLong();

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent e) {
            accumulate(e.getReasoningMessage());
        } else if (event instanceof PostSummaryEvent e) {
            // maxIters 超限后的总结也是一次真实 LLM 调用，一并计入
            accumulate(e.getSummaryMessage());
        } else if (event instanceof PostCallEvent e) {
            long input = inputTokens.get();
            long output = outputTokens.get();
            logger.info("[TOKEN_USAGE] {} 执行结束, 输入tokens={}, 输出tokens={}, 总计tokens={}",
                    e.getAgent().getName(), input, output, input + output);
        }
        return Mono.just(event);
    }

    private void accumulate(Msg msg) {
        ChatUsage usage = msg != null ? msg.getChatUsage() : null;
        if (usage != null) {
            inputTokens.addAndGet(usage.getInputTokens());
            outputTokens.addAndGet(usage.getOutputTokens());
        }
    }
}
