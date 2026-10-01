package com.gogo.travel.business.approval.service;

import com.gogo.travel.business.approval.entity.ApprovalRecord;
import com.gogo.travel.business.approval.entity.ApprovalRecordStatus;
import com.gogo.travel.business.approval.repo.ApprovalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApprovalService 单元测试：提交、决策幂等短路、取消幂等。
 *
 */
@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    @Mock
    private ApprovalRepository approvalRepository;

    @InjectMocks
    private ApprovalService approvalService;

    private static ApprovalRecord record(String processInstanceId, ApprovalRecordStatus status) {
        return new ApprovalRecord(processInstanceId, "u_1", "出差", status,
                "{}", null, Instant.now(), Instant.now());
    }

    // ============ submit ============

    @Test
    void submit_savesPendingRecordWithOrderLink() {
        ApprovalRecord result = approvalService.submit("u_1", "北京出差", "{\"k\":\"v\"}", "to_1");

        ArgumentCaptor<ApprovalRecord> captor = ArgumentCaptor.forClass(ApprovalRecord.class);
        verify(approvalRepository).save(captor.capture());
        ApprovalRecord saved = captor.getValue();
        assertTrue(saved.getProcessInstanceId().startsWith("pi_"));
        assertEquals(ApprovalRecordStatus.PENDING, saved.getStatus());
        assertEquals("to_1", saved.getOrderId());
        assertEquals("北京出差", saved.getTitle());
        assertEquals(result.getProcessInstanceId(), saved.getProcessInstanceId());
    }

    @Test
    void submit_generatesDistinctIds() {
        // 随机后缀保证同一毫秒内连续提交也不撞 ID
        ApprovalRecord first = approvalService.submit("u_1", "t1", "{}", "to_1");
        ApprovalRecord second = approvalService.submit("u_1", "t2", "{}", "to_2");
        assertNotEquals(first.getProcessInstanceId(), second.getProcessInstanceId());
    }

    // ============ decide ============

    @Test
    void decide_pending_approvedWithRemark() {
        when(approvalRepository.findByProcessInstanceId("pi_1"))
                .thenReturn(Optional.of(record("pi_1", ApprovalRecordStatus.PENDING)));

        Optional<ApprovalRecord> result = approvalService.decide("pi_1", true, "同意");

        assertTrue(result.isPresent());
        assertEquals(ApprovalRecordStatus.APPROVED, result.get().getStatus());
        assertEquals("同意", result.get().getRemark());
        verify(approvalRepository).save(result.get());
    }

    @Test
    void decide_pending_rejected() {
        when(approvalRepository.findByProcessInstanceId("pi_1"))
                .thenReturn(Optional.of(record("pi_1", ApprovalRecordStatus.PENDING)));

        Optional<ApprovalRecord> result = approvalService.decide("pi_1", false, "预算不足");

        assertTrue(result.isPresent());
        assertEquals(ApprovalRecordStatus.REJECTED, result.get().getStatus());
    }

    @Test
    void decide_nonPending_idempotentSkip() {
        // 已处理过的审批单：幂等短路，返回 empty 且不落库
        when(approvalRepository.findByProcessInstanceId("pi_1"))
                .thenReturn(Optional.of(record("pi_1", ApprovalRecordStatus.APPROVED)));

        Optional<ApprovalRecord> result = approvalService.decide("pi_1", false, null);

        assertTrue(result.isEmpty());
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void decide_notFound_returnsEmpty() {
        when(approvalRepository.findByProcessInstanceId("pi_404")).thenReturn(Optional.empty());
        assertTrue(approvalService.decide("pi_404", true, null).isEmpty());
    }

    // ============ cancel ============

    @Test
    void cancel_notFound_returnsFalse() {
        when(approvalRepository.findByProcessInstanceId("pi_404")).thenReturn(Optional.empty());
        assertFalse(approvalService.cancel("pi_404"));
    }

    @Test
    void cancel_alreadyCancelled_idempotentTrue() {
        when(approvalRepository.findByProcessInstanceId("pi_1"))
                .thenReturn(Optional.of(record("pi_1", ApprovalRecordStatus.CANCELLED)));

        assertTrue(approvalService.cancel("pi_1"));
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void cancel_pending_setsCancelled() {
        ApprovalRecord pending = record("pi_1", ApprovalRecordStatus.PENDING);
        when(approvalRepository.findByProcessInstanceId("pi_1")).thenReturn(Optional.of(pending));

        assertTrue(approvalService.cancel("pi_1"));
        assertEquals(ApprovalRecordStatus.CANCELLED, pending.getStatus());
        verify(approvalRepository).save(pending);
    }
}
