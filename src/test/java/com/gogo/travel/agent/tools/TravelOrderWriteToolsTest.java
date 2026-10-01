package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.business.approval.entity.ApprovalRecord;
import com.gogo.travel.business.approval.entity.ApprovalRecordStatus;
import com.gogo.travel.business.approval.service.ApprovalService;
import com.gogo.travel.business.booking.repo.BookingRecordRepository;
import com.gogo.travel.business.order.entity.TravelOrder;
import com.gogo.travel.business.order.entity.TravelOrderStatus;
import com.gogo.travel.business.order.repo.TravelOrderRepository;
import com.gogo.travel.business.order.service.TravelOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TravelOrderWriteTools 单元测试：
 * submit 的入参校验/幂等命中/落库验证，cancel 的状态机与"清空会话关联差旅单"，
 * modify 的空变更短路与 APPROVED 二次确认。
 *
 */
@ExtendWith(MockitoExtension.class)
class TravelOrderWriteToolsTest {

    private static final String USER_ID = "u_test";

    @Mock
    private TravelOrderRepository travelOrderRepository;
    @Mock
    private TravelOrderService travelOrderService;
    @Mock
    private ApprovalService approvalService;
    @Mock
    private BookingRecordRepository bookingRecordRepository;

    @InjectMocks
    private TravelOrderWriteTools tools;

    private AgentSessionContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new AgentSessionContext(USER_ID, "s_test");
    }

    private TravelOrder order(String orderId, TravelOrderStatus status) {
        return TravelOrder.builder()
                .orderId(orderId).userId(USER_ID)
                .departureCity("北京").destination("上海")
                .departureDate("2026-08-01").returnDate("2026-08-03")
                .purpose("参加会议").status(status)
                .build();
    }

    private static ApprovalRecord approval(String processInstanceId) {
        return new ApprovalRecord(processInstanceId, USER_ID, "出差", ApprovalRecordStatus.PENDING,
                "{}", null, Instant.now(), Instant.now());
    }

    // ============ submit_travel_approval ============

    @Test
    void submit_invalidDateFormat_rejected() {
        JSONObject o = JSON.parseObject(tools.submitTravelApproval(ctx, "上海", "北京", "2026/08/01", "2026-08-03", "开会"));
        assertFalse(o.getBooleanValue("success"));
        assertEquals("INVALID_PARAM", o.getString("errorCode"));
        verifyNoInteractions(travelOrderService);
    }

    @Test
    void submit_departureAfterReturn_rejected() {
        JSONObject o = JSON.parseObject(tools.submitTravelApproval(ctx, "上海", "北京", "2026-08-05", "2026-08-03", "开会"));
        assertFalse(o.getBooleanValue("success"));
        assertEquals("INVALID_DATE_RANGE", o.getString("errorCode"));
    }

    @Test
    void submit_duplicateActiveOrder_idempotentHit() {
        // 已存在出行要素完全一致的生效中差旅单：直接返回已有单，不重复创建
        TravelOrder dup = order("to_dup", TravelOrderStatus.SUBMITTED);
        dup.setApprovalId("pi_dup");
        when(travelOrderRepository.findActiveByUserIdAndDateRange(eq(USER_ID), any(), eq("2026-08-01"), eq("2026-08-03")))
                .thenReturn(List.of(dup));
        when(approvalService.findByProcessInstanceId("pi_dup")).thenReturn(Optional.of(approval("pi_dup")));

        JSONObject o = JSON.parseObject(tools.submitTravelApproval(ctx, "上海", "北京", "2026-08-01", "2026-08-03", "参加会议"));

        assertTrue(o.getBooleanValue("success"));
        assertEquals("to_dup", o.getString("orderId"));
        assertTrue(o.getString("message").contains("已存在"));
        assertEquals("to_dup", ctx.getTravelOrderId());
        verifyNoInteractions(travelOrderService);
    }

    @Test
    void submit_goldenPath_setsSessionTravelOrderId() {
        when(travelOrderRepository.findActiveByUserIdAndDateRange(eq(USER_ID), any(), anyString(), anyString()))
                .thenReturn(List.of());
        when(travelOrderService.createAndSubmit(any(TravelOrder.class))).thenAnswer(inv -> {
            TravelOrder order = inv.getArgument(0);
            order.setOrderId("to_1");
            order.setStatus(TravelOrderStatus.SUBMITTED);
            order.setApprovalId("pi_1");
            return approval("pi_1");
        });
        when(travelOrderRepository.findByOrderId("to_1"))
                .thenReturn(Optional.of(order("to_1", TravelOrderStatus.SUBMITTED)));

        JSONObject o = JSON.parseObject(tools.submitTravelApproval(ctx, "上海", "北京", "2026-08-01", "2026-08-03", "参加会议"));

        assertTrue(o.getBooleanValue("success"));
        assertEquals("to_1", o.getString("orderId"));
        assertEquals("pi_1", o.getString("processInstanceId"));
        assertEquals("to_1", ctx.getTravelOrderId());
    }

    @Test
    void submit_verifyStatusMismatch_reportsVerifyFailed() {
        when(travelOrderRepository.findActiveByUserIdAndDateRange(eq(USER_ID), any(), anyString(), anyString()))
                .thenReturn(List.of());
        when(travelOrderService.createAndSubmit(any(TravelOrder.class))).thenAnswer(inv -> {
            TravelOrder order = inv.getArgument(0);
            order.setOrderId("to_1");
            return approval("pi_1");
        });
        // 事务后查库状态仍是 DRAFT：验证应拦截
        when(travelOrderRepository.findByOrderId("to_1"))
                .thenReturn(Optional.of(order("to_1", TravelOrderStatus.DRAFT)));

        JSONObject o = JSON.parseObject(tools.submitTravelApproval(ctx, "上海", "北京", "2026-08-01", "2026-08-03", "参加会议"));

        assertFalse(o.getBooleanValue("success"));
        assertEquals("VERIFY_FAILED", o.getString("errorCode"));
    }

    // ============ cancel_travel_order ============

    @Test
    void cancel_orderNotFound_rejected() {
        when(travelOrderRepository.findByOrderId("to_404")).thenReturn(Optional.empty());
        JSONObject o = JSON.parseObject(tools.cancelTravelOrder(ctx, "to_404", null, null));
        assertEquals("ORDER_NOT_FOUND", o.getString("errorCode"));
    }

    @Test
    void cancel_wrongOwner_permissionDenied() {
        TravelOrder other = order("to_1", TravelOrderStatus.SUBMITTED);
        other.setUserId("u_other");
        when(travelOrderRepository.findByOrderId("to_1")).thenReturn(Optional.of(other));

        JSONObject o = JSON.parseObject(tools.cancelTravelOrder(ctx, "to_1", null, null));

        assertEquals("PERMISSION_DENIED", o.getString("errorCode"));
        verifyNoInteractions(travelOrderService);
    }

    @Test
    void cancel_alreadyCancelled_invalidState() {
        when(travelOrderRepository.findByOrderId("to_1"))
                .thenReturn(Optional.of(order("to_1", TravelOrderStatus.CANCELLED)));
        JSONObject o = JSON.parseObject(tools.cancelTravelOrder(ctx, "to_1", null, null));
        assertEquals("INVALID_STATE", o.getString("errorCode"));
    }

    @Test
    void cancel_approvedWithoutForce_needConfirm() {
        when(travelOrderRepository.findByOrderId("to_1"))
                .thenReturn(Optional.of(order("to_1", TravelOrderStatus.APPROVED)));

        JSONObject o = JSON.parseObject(tools.cancelTravelOrder(ctx, "to_1", null, null));

        assertFalse(o.getBooleanValue("success"));
        assertTrue(o.getBooleanValue("needConfirm"));
        verifyNoInteractions(travelOrderService);
    }

    @Test
    void cancel_goldenPath_clearsSessionTravelOrderId() {
        TravelOrder order = order("to_1", TravelOrderStatus.SUBMITTED);
        when(travelOrderRepository.findByOrderId("to_1")).thenReturn(Optional.of(order));
        when(travelOrderService.cancelWithApproval(order)).thenAnswer(inv -> {
            order.setStatus(TravelOrderStatus.CANCELLED);
            return new TravelOrderService.CancelOutcome(true, true, "pi_old");
        });
        when(bookingRecordRepository.findByUserIdAndTravelOrderId(USER_ID, "to_1")).thenReturn(List.of());
        ctx.setTravelOrderId("to_1");

        JSONObject o = JSON.parseObject(tools.cancelTravelOrder(ctx, "to_1", "行程有变", null));

        assertTrue(o.getBooleanValue("success"));
        assertTrue(o.getBooleanValue("approvalCancelled"));
        // 被取消的正是会话当前关联的差旅单：必须清空，防止后续预订挂到已取消的单上
        assertNull(ctx.getTravelOrderId());
    }

    @Test
    void cancel_unrelatedSessionOrder_notCleared() {
        TravelOrder order = order("to_1", TravelOrderStatus.SUBMITTED);
        when(travelOrderRepository.findByOrderId("to_1")).thenReturn(Optional.of(order));
        when(travelOrderService.cancelWithApproval(order)).thenAnswer(inv -> {
            order.setStatus(TravelOrderStatus.CANCELLED);
            return new TravelOrderService.CancelOutcome(true, false, null);
        });
        when(bookingRecordRepository.findByUserIdAndTravelOrderId(USER_ID, "to_1")).thenReturn(List.of());
        ctx.setTravelOrderId("to_other");

        tools.cancelTravelOrder(ctx, "to_1", null, null);

        // 会话关联的是别的单，不应被误清
        assertEquals("to_other", ctx.getTravelOrderId());
    }

    // ============ modify_travel_order ============

    @Test
    void modify_noFieldChanged_shortCircuits() {
        when(travelOrderRepository.findByOrderId("to_1"))
                .thenReturn(Optional.of(order("to_1", TravelOrderStatus.SUBMITTED)));

        // 提交的字段与当前值完全一致：应幂等短路，不撤审重提
        JSONObject o = JSON.parseObject(tools.modifyTravelOrder(ctx, "to_1", "上海", null, "2026-08-01", null, null, null));

        assertTrue(o.getBooleanValue("success"));
        assertTrue(o.getString("message").contains("未做任何修改"));
        verify(travelOrderService, never()).modifyAndResubmit(any());
    }

    @Test
    void modify_approvedWithoutForce_needConfirm() {
        when(travelOrderRepository.findByOrderId("to_1"))
                .thenReturn(Optional.of(order("to_1", TravelOrderStatus.APPROVED)));

        JSONObject o = JSON.parseObject(tools.modifyTravelOrder(ctx, "to_1", "深圳", null, null, null, null, null));

        assertFalse(o.getBooleanValue("success"));
        assertTrue(o.getBooleanValue("needConfirm"));
        verify(travelOrderService, never()).modifyAndResubmit(any());
    }

    @Test
    void modify_effectiveDateRangeInvalid_rejected() {
        when(travelOrderRepository.findByOrderId("to_1"))
                .thenReturn(Optional.of(order("to_1", TravelOrderStatus.SUBMITTED)));

        // 只改出发日期，晚于原返回日期：新旧值综合校验应拦截
        JSONObject o = JSON.parseObject(tools.modifyTravelOrder(ctx, "to_1", null, null, "2026-08-10", null, null, null));

        assertFalse(o.getBooleanValue("success"));
        assertEquals("INVALID_DATE_RANGE", o.getString("errorCode"));
    }
}
