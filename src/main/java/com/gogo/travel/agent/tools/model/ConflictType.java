package com.gogo.travel.agent.tools.model;

/**
 * 行程冲突类型。
 * <p>fastjson2 默认按 {@code name()} 序列化，输出 JSON 与原字符串常量完全一致。
 *
 */
public enum ConflictType {

    /** 时间重叠且同城：重复提交 */
    TIME_OVERLAP_SAME_CITY,

    /** 时间重叠且跨城：物理不可能同时身处两地 */
    TIME_OVERLAP_DIFF_CITY,

    /** 同日跨城衔接时间不足 */
    TRANSIT_TOO_TIGHT,

    /** 相邻日跨城路径断裂 / 衔接不足 */
    DISCONNECTED_ROUTE
}
