package com.gogo.travel.agent.tools.review;

import com.alibaba.fastjson2.JSONObject;

import java.util.Map;

/**
 * 单维度校验结果。
 *
 * @param dimension 维度名称
 * @param status    校验状态：pass / warning / fail
 * @param detail    详细描述
 */
public record CheckResult(String dimension, String status, String detail) {

    public JSONObject toJson() {
        return new JSONObject(Map.of("dimension", dimension, "status", status, "detail", detail));
    }
}
