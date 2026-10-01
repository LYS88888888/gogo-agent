package com.gogo.travel.agent.order;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.tools.TravelOrderConflictTools;
import com.gogo.travel.agent.tools.model.CityTransitTimeService;
import com.gogo.travel.business.order.entity.TravelOrder;
import com.gogo.travel.business.order.entity.TravelOrderStatus;
import com.gogo.travel.business.order.repo.TravelOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TravelOrderConflictTools 单元测试：
 * 入参校验、五分支日期关系（重叠/同日/次日/反向）、三档严重度、反向拆单提示、
 * 排除自身、按严重度排序，以及"相邻日查询窗口外扩 1 天"的回归验证。
 *
 */
@ExtendWith(MockitoExtension.class)
class TravelOrderConflictToolsTest {

    private static final String USER_ID = "u_test";

    @Mock
    private TravelOrderRepository travelOrderRepository;

    @Mock
    private CityTransitTimeService cityTransitTimeService;

    @InjectMocks
    private TravelOrderConflictTools tools;

    private AgentSessionContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new AgentSessionContext(USER_ID, "s_test");
    }

    private TravelOrder order(String orderId, String depCity, String dest, String depDate, String retDate) {
        return TravelOrder.builder()
                .orderId(orderId).userId(USER_ID)
                .departureCity(depCity).destination(dest)
                .departureDate(depDate).returnDate(retDate)
                .status(TravelOrderStatus.SUBMITTED)
                .build();
    }

    private void stubCandidates(TravelOrder... orders) {
        when(travelOrderRepository.findActiveByUserIdAndDateRange(eq(USER_ID), any(), anyString(), anyString()))
                .thenReturn(new ArrayList<>(List.of(orders)));
    }

    private JSONObject check(String depCity, String dest, String depDate, String retDate, String excludeId) {
        return JSON.parseObject(tools.checkTravelOrderConflicts(ctx, depCity, dest, depDate, retDate, excludeId));
    }

    private JSONObject firstConflict(JSONObject report) {
        JSONArray conflicts = report.getJSONArray("conflicts");
        assertFalse(conflicts.isEmpty());
        return conflicts.getJSONObject(0);
    }

    // ============ 入参校验 ============

    @Test
    void invalidParams_reportedWithoutConflict() {
        JSONObject o = check(" ", "上海", "2026-08-01", "2026-08-03", null);
        assertFalse(o.getBooleanValue("has_conflict"));
        assertTrue(o.getString("summary").contains("入参校验失败"));
    }

    @Test
    void departureAfterReturn_rejected() {
        JSONObject o = check("北京", "上海", "2026-08-05", "2026-08-03", null);
        assertFalse(o.getBooleanValue("has_conflict"));
        assertTrue(o.getString("summary").contains("修正日期"));
    }

    // ============ 查询窗口回归：必须外扩 1 天覆盖相邻日差旅单 ============

    @Test
    void queryWindow_widenedByOneDayOnBothSides() {
        stubCandidates();
        check("北京", "上海", "2026-08-01", "2026-08-03", null);

        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(travelOrderRepository).findActiveByUserIdAndDateRange(eq(USER_ID), any(), from.capture(), to.capture());
        assertEquals("2026-07-31", from.getValue());
        assertEquals("2026-08-04", to.getValue());
    }

    // ============ ① 日期重叠 ============

    @Test
    void overlap_sameCityPair_lowSeverity() {
        // 已有单城市带"市"后缀，归一化后视为同城重复
        stubCandidates(order("to_1", "北京市", "上海市", "2026-08-01", "2026-08-03"));

        JSONObject c = firstConflict(check("北京", "上海", "2026-08-02", "2026-08-04", null));

        assertEquals("TIME_OVERLAP_SAME_CITY", c.getString("type"));
        assertEquals("LOW", c.getString("severity"));
    }

    @Test
    void overlap_differentCity_highSeverity() {
        stubCandidates(order("to_1", "北京", "广州", "2026-08-01", "2026-08-03"));

        JSONObject c = firstConflict(check("北京", "上海", "2026-08-02", "2026-08-04", null));

        assertEquals("TIME_OVERLAP_DIFF_CITY", c.getString("type"));
        assertEquals("HIGH", c.getString("severity"));
        assertTrue(c.getString("description").contains("物理上不可能"));
    }

    @Test
    void overlap_reversedDirection_highWithRoundTripHint() {
        // 已有 上海→北京，候选 北京→上海：方向恰好相反，应在文案中点破供用户自判
        stubCandidates(order("to_1", "上海", "北京", "2026-08-01", "2026-08-03"));

        JSONObject c = firstConflict(check("北京", "上海", "2026-08-01", "2026-08-03", null));

        assertEquals("HIGH", c.getString("severity"));
        assertTrue(c.getString("description").contains("方向恰好相反"));
    }

    // ============ ② 同日衔接：三档严重度 ============

    @Test
    void sameDayTransit_over24h_high() {
        stubCandidates(order("to_1", "北京", "拉萨", "2026-07-28", "2026-08-01"));
        when(cityTransitTimeService.estimateMinutes("拉萨", "北京")).thenReturn(25 * 60);

        JSONObject c = firstConflict(check("北京", "上海", "2026-08-01", "2026-08-03", null));

        assertEquals("TRANSIT_TOO_TIGHT", c.getString("type"));
        assertEquals("HIGH", c.getString("severity"));
    }

    @Test
    void sameDayTransit_over8h_mediumTight() {
        stubCandidates(order("to_1", "北京", "乌鲁木齐", "2026-07-28", "2026-08-01"));
        when(cityTransitTimeService.estimateMinutes("乌鲁木齐", "北京")).thenReturn(9 * 60);

        JSONObject c = firstConflict(check("北京", "上海", "2026-08-01", "2026-08-03", null));

        assertEquals("MEDIUM", c.getString("severity"));
        assertTrue(c.getString("description").contains("非常紧张"));
    }

    @Test
    void sameDayTransit_within8h_mediumFeasible() {
        stubCandidates(order("to_1", "北京", "杭州", "2026-07-28", "2026-08-01"));
        when(cityTransitTimeService.estimateMinutes("杭州", "北京")).thenReturn(4 * 60);

        JSONObject c = firstConflict(check("北京", "上海", "2026-08-01", "2026-08-03", null));

        assertEquals("MEDIUM", c.getString("severity"));
        assertTrue(c.getString("suggestion").contains("缓冲"));
    }

    // ============ ③ 次日衔接 ============

    @Test
    void adjacentDay_longTransit_disconnectedRoute() {
        // 已有单 7/31 结束于乌鲁木齐，候选 8/1 从北京出发：1 天衔接不足
        stubCandidates(order("to_1", "北京", "乌鲁木齐", "2026-07-28", "2026-07-31"));
        when(cityTransitTimeService.estimateMinutes("乌鲁木齐", "北京")).thenReturn(10 * 60);

        JSONObject c = firstConflict(check("北京", "上海", "2026-08-01", "2026-08-03", null));

        assertEquals("DISCONNECTED_ROUTE", c.getString("type"));
        assertEquals("MEDIUM", c.getString("severity"));
    }

    @Test
    void adjacentDay_shortTransit_noConflict() {
        stubCandidates(order("to_1", "北京", "杭州", "2026-07-28", "2026-07-31"));
        when(cityTransitTimeService.estimateMinutes("杭州", "北京")).thenReturn(4 * 60);

        JSONObject o = check("北京", "上海", "2026-08-01", "2026-08-03", null);

        assertFalse(o.getBooleanValue("has_conflict"));
    }

    @Test
    void adjacentDay_sameCity_noConflictWithoutEstimation() {
        // 已有单结束于北京，候选次日从北京出发：同城无需估算
        stubCandidates(order("to_1", "上海", "北京", "2026-07-28", "2026-07-31"));

        JSONObject o = check("北京", "上海", "2026-08-01", "2026-08-03", null);

        assertFalse(o.getBooleanValue("has_conflict"));
        verify(cityTransitTimeService, never()).estimateMinutes(anyString(), anyString());
    }

    // ============ 排除自身与排序 ============

    @Test
    void excludeOrderId_removesSelf() {
        stubCandidates(order("to_self", "北京", "上海", "2026-08-01", "2026-08-03"));

        JSONObject o = check("北京", "上海", "2026-08-01", "2026-08-03", "to_self");

        assertFalse(o.getBooleanValue("has_conflict"));
    }

    @Test
    void conflicts_sortedBySeverityDesc() {
        // LOW（同城重复）在前插入、HIGH（跨城重叠）在后插入，输出应 HIGH 在首位
        stubCandidates(
                order("to_1", "北京", "上海", "2026-08-01", "2026-08-03"),
                order("to_2", "北京", "广州", "2026-08-01", "2026-08-03"));

        JSONObject o = check("北京", "上海", "2026-08-02", "2026-08-04", null);

        assertEquals(2, o.getIntValue("total_conflicts"));
        assertEquals("HIGH", o.getJSONArray("conflicts").getJSONObject(0).getString("severity"));
        assertEquals("LOW", o.getJSONArray("conflicts").getJSONObject(1).getString("severity"));
    }
}
