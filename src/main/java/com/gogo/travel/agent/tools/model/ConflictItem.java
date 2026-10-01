package com.gogo.travel.agent.tools.model;

import com.alibaba.fastjson2.annotation.JSONField;

/**
 * 单条行程冲突明细。
 */
public class ConflictItem {

    /** 冲突类型 */
    @JSONField(name = "type")
    private ConflictType type;

    /** 严重等级：HIGH / MEDIUM / LOW */
    @JSONField(name = "severity")
    private ConflictSeverity severity;

    /** 冲突的已有差旅单 ID */
    @JSONField(name = "order_id")
    private String orderId;

    /** 已有差旅单的简要描述（出发→到达 + 日期） */
    @JSONField(name = "order_summary")
    private String orderSummary;

    /** 人类可读的冲突原因 */
    @JSONField(name = "description")
    private String description;

    /** 调整建议（自动生成） */
    @JSONField(name = "suggestion")
    private String suggestion;

    public ConflictType getType() { return type; }
    public void setType(ConflictType type) { this.type = type; }

    public ConflictSeverity getSeverity() { return severity; }
    public void setSeverity(ConflictSeverity severity) { this.severity = severity; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getOrderSummary() { return orderSummary; }
    public void setOrderSummary(String orderSummary) { this.orderSummary = orderSummary; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
}
