package com.gogo.travel.agent.tools.planner;

import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行程规划中间计算上下文（构建阶段可变，构建完成后只读）。
 *
 * <p>由 ItineraryPlannerTool.buildCalcContext 构建，
 * 供 {@link CandidateRanker} 使用。
 *
 */
public final class CalcContext {

    public String error;
    public int comboCount;
    public JSONObject userRequest;
    public String preferences;
    public String weatherSummary;
    public JSONObject weights;
    public List<JSONObject> combos = new ArrayList<>();
    public Map<String, JSONObject> legTransport = new LinkedHashMap<>();
    public Map<String, JSONObject> legHotels = new LinkedHashMap<>();
}
