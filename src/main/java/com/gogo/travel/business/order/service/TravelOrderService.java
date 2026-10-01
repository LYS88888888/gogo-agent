package com.gogo.travel.business.order.service;

import com.alibaba.fastjson2.JSON;
import com.gogo.travel.business.approval.entity.ApprovalRecord;
import com.gogo.travel.business.approval.entity.ApprovalRecordStatus;
import com.gogo.travel.business.approval.service.ApprovalService;
import com.gogo.travel.business.order.entity.TravelOrder;
import com.gogo.travel.business.order.entity.TravelOrderStatus;
import com.gogo.travel.business.order.repo.TravelOrderRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 差旅单写操作事务服务：将「差旅单 + 审批单」的多步写操作包在同一本地事务中，
 * 保证部分失败时整体回滚，避免出现孤儿 DRAFT 差旅单或双向关联断裂的中间态。
 * <p>注意：当前审批单是纯本地 DB 写入（与差旅单同一数据源），可安全放入事务；
 * 若未来对接外部审批系统（如钉钉审批），外部调用不能包在 DB 事务里，
 * 需改为「先落库 + 补偿/outbox」模式。
 *
 */
@Service
public class TravelOrderService {

    private static final Logger logger = LoggerFactory.getLogger(TravelOrderService.class);

    @Autowired
    private TravelOrderRepository travelOrderRepository;
    @Autowired
    private ApprovalService approvalService;

    /**
     * 取消结果：orderCancelled=false 表示差旅单状态更新失败（此时未做任何审批撤销）
     */
    public record CancelOutcome(boolean orderCancelled, boolean approvalCancelled, String cancelledApprovalId) {
    }

    /**
     * 修改结果：旧审批单撤销情况 + 新提交的审批单
     */
    public record ModifyOutcome(String oldApprovalId, boolean oldApprovalCancelled, ApprovalRecord newApproval) {
    }

    /**
     * 创建差旅单并提交审批（事务原子）：
     * ① 差旅单落库（DRAFT）→ ② 提交审批单（PENDING）→ ③ 差旅单升级 SUBMITTED 并回写审批单ID。
     * 任一步失败整体回滚，入参 order 的 orderId/approvalId/status 会被就地更新。
     *
     * @return 新提交的审批单
     */
    @Transactional(rollbackFor = Exception.class)
    public ApprovalRecord createAndSubmit(TravelOrder order) {
        // ① 创建差旅单（DRAFT）
        order.setStatus(TravelOrderStatus.DRAFT);
        TravelOrder savedOrder = travelOrderRepository.save(order);
        String orderId = savedOrder.getOrderId();
        logger.info("[TravelOrderService] ① 差旅单已创建 orderId={}", orderId);

        // ② 构建审批表单并提交审批单
        ApprovalRecord record = approvalService.submit(savedOrder.getUserId(), savedOrder.getPurpose(),
                buildApprovalForm(savedOrder), orderId);
        logger.info("[TravelOrderService] ② 审批单已提交 processInstanceId={}", record.getProcessInstanceId());

        // ③ 差旅单写入审批单ID，状态升级为 SUBMITTED
        savedOrder.setApprovalId(record.getProcessInstanceId());
        savedOrder.setStatus(TravelOrderStatus.SUBMITTED);
        travelOrderRepository.save(savedOrder);
        logger.info("[TravelOrderService] ③ 双向关联完成：差旅单 {} ↔ 审批单 {}", orderId, record.getProcessInstanceId());
        return record;
    }

    /**
     * 取消差旅单并同步撤销关联审批单（事务原子）。
     * 差旅单状态更新失败时直接返回（此时事务内无任何变更）；审批撤销失败则整体回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public CancelOutcome cancelWithApproval(TravelOrder order) {
        String orderId = order.getOrderId();
        boolean orderCancelled = travelOrderRepository.updateStatus(orderId, TravelOrderStatus.CANCELLED);
        if (!orderCancelled) {
            return new CancelOutcome(false, false, null);
        }

        // 同步撤销关联审批单
        boolean approvalCancelled = false;
        String cancelledApprovalId = null;
        String approvalId = order.getApprovalId();
        if (!isBlank(approvalId)) {
            approvalCancelled = approvalService.cancel(approvalId);
            cancelledApprovalId = approvalId;
        } else {
            Optional<ApprovalRecord> approvalOpt = approvalService.findLatestByOrderId(orderId);
            if (approvalOpt.isPresent()) {
                ApprovalRecord ar = approvalOpt.get();
                if (ApprovalRecordStatus.CANCELLED != ar.getStatus()) {
                    approvalCancelled = approvalService.cancel(ar.getProcessInstanceId());
                    cancelledApprovalId = ar.getProcessInstanceId();
                }
            }
        }
        logger.info("[TravelOrderService] 差旅单 {} 已取消，关联审批撤销={}", orderId, approvalCancelled);
        return new CancelOutcome(true, approvalCancelled, cancelledApprovalId);
    }

    /**
     * 修改差旅单并撤审重提（事务原子）：
     * 撤销旧审批单 → 差旅单重置 DRAFT 并保存字段变更 → 提交新审批单 → 差旅单升级 SUBMITTED 并关联新审批。
     * 入参 order 需已完成字段更新，其 approvalId/status 会被就地更新。
     */
    @Transactional(rollbackFor = Exception.class)
    public ModifyOutcome modifyAndResubmit(TravelOrder order) {
        String orderId = order.getOrderId();

        // 撤销旧审批单
        boolean oldApprovalCancelled = false;
        String oldApprovalId = order.getApprovalId();
        if (!isBlank(oldApprovalId)) {
            oldApprovalCancelled = approvalService.cancel(oldApprovalId);
        } else {
            Optional<ApprovalRecord> approvalOpt = approvalService.findLatestByOrderId(orderId);
            if (approvalOpt.isPresent()) {
                oldApprovalId = approvalOpt.get().getProcessInstanceId();
                oldApprovalCancelled = approvalService.cancel(oldApprovalId);
            }
        }

        // 重置为草稿并保存字段变更
        order.setStatus(TravelOrderStatus.DRAFT);
        order.setApprovalId(null);
        travelOrderRepository.save(order);

        // 提交新审批单
        ApprovalRecord newRecord = approvalService.submit(order.getUserId(), order.getPurpose(),
                buildApprovalForm(order), orderId);

        // 差旅单关联新审批单
        order.setApprovalId(newRecord.getProcessInstanceId());
        order.setStatus(TravelOrderStatus.SUBMITTED);
        travelOrderRepository.save(order);
        logger.info("[TravelOrderService] 差旅单 {} 修改完成，旧审批 {} 已撤销={}，新审批={}",
                orderId, oldApprovalId, oldApprovalCancelled, newRecord.getProcessInstanceId());
        return new ModifyOutcome(oldApprovalId, oldApprovalCancelled, newRecord);
    }

    /**
     * 审批决策并同步差旅单状态（事务原子）：
     * 审批单 PENDING → APPROVED/REJECTED，关联差旅单同步为 APPROVED/REJECTED。
     * 供管理员审批接口与钉钉审批回调共用。
     *
     * @return 更新后的审批单；审批单不存在或非待审批状态时返回 empty（幂等，不做任何变更）
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<ApprovalRecord> decideAndSyncOrder(String processInstanceId, boolean agree, String remark) {
        Optional<ApprovalRecord> updated = approvalService.decide(processInstanceId, agree, remark);
        if (updated.isEmpty()) {
            return updated;
        }
        ApprovalRecord record = updated.get();
        if (!isBlank(record.getOrderId())) {
            TravelOrderStatus orderStatus = agree ? TravelOrderStatus.APPROVED : TravelOrderStatus.REJECTED;
            travelOrderRepository.updateStatus(record.getOrderId(), orderStatus);
            logger.info("[TravelOrderService] 审批 {} 已决策 agree={}，差旅单 {} 同步为 {}",
                    processInstanceId, agree, record.getOrderId(), orderStatus.getCode());
        }
        return updated;
    }

    /**
     * 构建审批表单 JSON 字符串（序列化交给 fastjson2，避免 purpose 等自由文本含引号/反斜杠时手拼出非法 JSON）
     */
    private static String buildApprovalForm(TravelOrder order) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("orderId", order.getOrderId());
        form.put("userId", order.getUserId());
        form.put("purpose", order.getPurpose());
        form.put("destination", order.getDestination());
        form.put("departureCity", order.getDepartureCity());
        form.put("departureDate", order.getDepartureDate());
        form.put("returnDate", order.getReturnDate());
        return JSON.toJSONString(form);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
