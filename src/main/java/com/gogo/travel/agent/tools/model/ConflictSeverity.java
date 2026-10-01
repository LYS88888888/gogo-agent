package com.gogo.travel.agent.tools.model;

/**
 * 行程冲突严重等级。
 * <ul>
 *   <li>{@code HIGH}   — 物理上不可能：同时身处两地、出发当日无法赶到机场等。强烈建议用户调整。</li>
 *   <li>{@code MEDIUM} — 衔接紧张或路径断裂：行程间跨城交通时间不足。建议调整。</li>
 *   <li>{@code LOW}    — 仅作提示：同时间段相同城市重复提交等。允许直接继续。</li>
 * </ul>
 * <p>fastjson2 默认按 {@code name()} 序列化，输出 JSON 与原字符串常量完全一致。
 *
 */
public enum ConflictSeverity {

    /** 物理上不可能 */
    HIGH(0),
    /** 衔接紧张或路径断裂 */
    MEDIUM(1),
    /** 仅作提示 */
    LOW(2);

    private final int rank;

    ConflictSeverity(int rank) {
        this.rank = rank;
    }

    /** 排序权重：数值越小越严重 */
    public int rank() {
        return rank;
    }
}
