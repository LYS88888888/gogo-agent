package com.gogo.travel.business.chat.repository;

import com.gogo.travel.business.chat.entity.ChatConversation;
import com.gogo.travel.business.chat.entity.ChatMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 对话历史数据访问接口。
 *
 */
public interface ChatHistoryRepository {

    Optional<ChatConversation> findConversationById(String conversationId);

    List<ChatConversation> findConversationsByUserId(String userId);

    List<ChatMessage> findMessagesByConversationId(String conversationId);

    /**
     * 查询某会话下最近 N 条消息（按时间正序返回）。
     *
     * @param conversationId 会话 ID
     * @param limit          最大条数
     * @return 按时间正序排列的消息列表
     */
    List<ChatMessage> findRecentMessages(String conversationId, int limit);

    Optional<ChatMessage> findMessageById(String messageId);

    void saveConversation(ChatConversation conversation);

    void saveMessage(ChatMessage message);

    void updateTitle(String conversationId, String title);

    /**
     * 更新指定消息的用户反馈。
     *
     * @param messageId 消息 ID
     * @param feedback  反馈值（LIKE / DISLIKE / null 表示清空）
     * @param feedbackAt 反馈时间
     * @return 受影响行数（用于上层判断消息是否存在）
     */
    int updateFeedback(String messageId, String feedback, Instant feedbackAt);

    void deleteConversation(String conversationId);

    void deleteMessagesByConversationId(String conversationId);
}
