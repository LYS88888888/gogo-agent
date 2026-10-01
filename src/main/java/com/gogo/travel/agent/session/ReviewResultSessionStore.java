package com.gogo.travel.agent.session;

import com.gogo.travel.agent.session.record.ReviewResultState;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 行程审核结果的 Session 存储。
 *
 */
@Component
public class ReviewResultSessionStore {

    private static final Logger logger = LoggerFactory.getLogger(ReviewResultSessionStore.class);

    private static final String REVIEW_RESULT_FIELD = "reviewResult";
    private static final String REVIEW_RESULT_SESSION_KEY_SUFFIX = ":review_result";

    @Autowired
    private Session session;

    /**
     * 将审核报告 JSON 持久化到 Session。
     *
     * @param sessionId       对话会话 ID
     * @param reviewResultJson 审核报告完整 JSON
     */
    public void save(String sessionId, String reviewResultJson) {
        try {
            session.save(buildSessionKey(sessionId), REVIEW_RESULT_FIELD,
                    new ReviewResultState(reviewResultJson));
            logger.debug("[ReviewResultStore] 已持久化审核结果, sessionId={}", sessionId);
        } catch (Exception e) {
            logger.error("[ReviewResultStore] 持久化审核结果失败, sessionId={}", sessionId, e);
        }
    }

    /**
     * 从 Session 加载上一轮审核报告 JSON。
     *
     * @param sessionId 对话会话 ID
     * @return 审核报告 JSON 字符串，若不存在则返回 null
     */
    public String load(String sessionId) {
        try {
            return session.get(buildSessionKey(sessionId), REVIEW_RESULT_FIELD, ReviewResultState.class)
                    .map(ReviewResultState::reviewResultJson)
                    .orElse(null);
        } catch (Exception e) {
            logger.error("[ReviewResultStore] 加载审核结果失败, sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 清除 Session 中的审核结果。
     *
     * @param sessionId 对话会话 ID
     */
    public void clear(String sessionId) {
        try {
            session.delete(buildSessionKey(sessionId));
            logger.debug("[ReviewResultStore] 已清除审核结果, sessionId={}", sessionId);
        } catch (Exception e) {
            logger.error("[ReviewResultStore] 清除审核结果失败, sessionId={}", sessionId, e);
        }
    }

    private SimpleSessionKey buildSessionKey(String sessionId) {
        return SimpleSessionKey.of(sessionId + REVIEW_RESULT_SESSION_KEY_SUFFIX);
    }
}
