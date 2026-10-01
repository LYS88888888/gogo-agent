package com.gogo.travel.agent.tools.review;

import com.alibaba.fastjson2.JSONObject;

/**
 * 客观校验器接口：对行程方案执行单一维度的确定性校验。
 * <p>
 * 每个实现类负责一个校验维度（完整性、时间、出发目的地、预算、舱位、路径），
 * 便于独立单元测试和规则扩展。
 *
 */
public interface ObjectiveReviewer {

    /**
     * 对行程方案执行单一维度校验，结果写入 collector。
     *
     * @param itinerary 行程方案 JSON
     * @param policy    差旅政策 JSON，可能为空对象（无政策时）
     * @param collector 校验结果收集器
     */
    void review(JSONObject itinerary, JSONObject policy, ReviewCollector collector);

    /**
     * 城市名模糊匹配（共享工具方法）。
     * 无审批信息时不做校验，视为匹配。
     */
    static boolean cityMatches(String actual, String approved) {
        if (approved == null || actual == null) {
            return true;
        }
        return actual.contains(approved) || approved.contains(actual);
    }
}
