package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.business.approval.entity.ApprovalRecord;
import com.gogo.travel.business.approval.service.ApprovalService;
import com.gogo.travel.business.order.entity.TravelOrder;
import com.gogo.travel.business.order.entity.TravelOrderStatus;
import com.gogo.travel.business.order.mapper.TravelOrderMapper;
import com.gogo.travel.business.order.repo.TravelOrderRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

@Component
public class TravelOrderReadTools {

    private static final Logger logger = LoggerFactory.getLogger(TravelOrderReadTools.class);

    @Autowired
    private TravelOrderRepository travelOrderRepository;

    @Autowired
    private TravelOrderMapper travelOrderMapper;

    @Autowired
    private ApprovalService approvalService;


//废弃： https://thoughts.aliyun.com/workspaces/6963289eb0fc2e001bb052eb/docs/6a905bc1a7c8ff00013e3a0d
//    @Tool(name = "query_travel_order", description = "查询用户差旅单列表或指定差旅单详情。支持按状态、出发日期范围过滤。")
//    public String queryTravelOrder(
//            AgentSessionContext sessionCtx,
//            @ToolParam(name = "order_id", description = "差旅单ID，传入则直接返回该单详情，忽略其他筛选条件", required = false) String orderId,
//            @ToolParam(name = "status", description = "状态过滤，支持单个或逗号分隔的多个状态，如 DRAFT 或 DRAFT,SUBMITTED。可选值: DRAFT/SUBMITTED/APPROVED/REJECTED/COMPLETED/CANCELLED", required = false) String status,
//            @ToolParam(name = "start_date", description = "筛选出发日期不早于此日期的差旅单，格式 YYYY-MM-DD，可选", required = false) String startDate,
//            @ToolParam(name = "end_date", description = "筛选出发日期不晚于此日期的差旅单，格式 YYYY-MM-DD，可选", required = false) String endDate) {
//
//        String userId = sessionCtx.getUserId();
//        logger.info("[TOOL][query_travel_order] userId={}, orderId={}, status={}, startDate={}, endDate={}",
//                userId, orderId, status, startDate, endDate);
//
//        if (orderId != null && !orderId.isBlank()) {
//            TravelOrder order = travelOrderRepository.findByOrderId(orderId).orElse(null);
//            if (order != null) {
//                sessionCtx.setTravelOrderId(orderId);
//                sessionCtx.setCachedOrders(List.of(order));
//            }
//            String result = JSON.toJSONString(order != null ? order : "{\"error\":\"order not found\"}");
//            logger.info("[TOOL][query_travel_order] result={}", result);
//            return result;
//        }
//
//        List<TravelOrder> orders;
//        boolean hasDateFilter = (startDate != null && !startDate.isBlank())
//                || (endDate != null && !endDate.isBlank());
//
//        if (hasDateFilter) {
//            LambdaQueryWrapper<TravelOrder> wrapper = new LambdaQueryWrapper<TravelOrder>()
//                    .eq(TravelOrder::getUserId, userId);
//            if (status != null && !status.isBlank()) {
//                List<TravelOrderStatus> statusList = parseStatusList(status);
//                if (statusList == null) {
//                    return JSON.toJSONString(java.util.Map.of("error",
//                            "无效的状态参数: " + status + "，可选值: DRAFT/SUBMITTED/APPROVED/REJECTED/COMPLETED/CANCELLED"));
//                }
//                if (statusList.size() == 1) {
//                    wrapper.eq(TravelOrder::getStatus, statusList.get(0));
//                } else {
//                    wrapper.in(TravelOrder::getStatus, statusList);
//                }
//            }
//            if (startDate != null && !startDate.isBlank()) {
//                wrapper.ge(TravelOrder::getDepartureDate, startDate);
//            }
//            if (endDate != null && !endDate.isBlank()) {
//                wrapper.le(TravelOrder::getDepartureDate, endDate);
//            }
//            wrapper.orderByAsc(TravelOrder::getDepartureDate);
//            orders = travelOrderMapper.selectList(wrapper);
//        } else if (status != null && !status.isBlank()) {
//            List<TravelOrderStatus> statusList = parseStatusList(status);
//            if (statusList == null) {
//                return JSON.toJSONString(java.util.Map.of("error",
//                        "无效的状态参数: " + status + "，可选值: DRAFT/SUBMITTED/APPROVED/REJECTED/COMPLETED/CANCELLED"));
//            }
//            if (statusList.size() == 1) {
//                orders = travelOrderRepository.findByUserIdAndStatus(userId, statusList.get(0));
//            } else {
//                LambdaQueryWrapper<TravelOrder> wrapper = new LambdaQueryWrapper<TravelOrder>()
//                        .eq(TravelOrder::getUserId, userId)
//                        .in(TravelOrder::getStatus, statusList)
//                        .orderByAsc(TravelOrder::getDepartureDate);
//                orders = travelOrderMapper.selectList(wrapper);
//            }
//        } else {
//            orders = travelOrderRepository.findByUserId(userId);
//        }
//
//        String result = JSON.toJSONString(orders);
//        if (orders != null && orders.size() == 1) {
//            sessionCtx.setCachedOrders(orders);
//            logger.info("[TOOL][query_travel_order] result={}", result);
//            return result;
//        } else {
//            // 提示查询到多条，需要选择其中一条
//            return JSON.toJSONString(java.util.Map.of("error", "查询到多条差旅单，请指定 order_id", "orders", JSON.toJSONString(orders)));
//        }
//    }


    @Tool(name = "query_travel_order", description = "查询用户差旅单列表或指定差旅单详情。支持按状态、出发日期范围过滤。")
    public String queryTravelOrder(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "origin", description = "出发地城市，如'上海'")
            String origin,
            @ToolParam(name = "destination", description = "目的地城市，如'杭州'")
            String destination,
            @ToolParam(name = "departure_date", description = "出发日期，格式 YYYY-MM-DD")
            String departureDate) {

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][query_travel_order] userId={}, origin={}, destination={}, departureDate={}",
                userId, origin, destination, departureDate);

        LambdaQueryWrapper<TravelOrder> wrapper = new LambdaQueryWrapper<TravelOrder>()
                .eq(TravelOrder::getUserId, userId)
                .eq(TravelOrder::getDepartureCity, origin)
                .eq(TravelOrder::getDestination, destination)
                .eq(TravelOrder::getDepartureDate, departureDate);

        TravelOrder travelOrder = travelOrderMapper.selectOne(wrapper);

        if (travelOrder != null) {
            if(!travelOrder.getUserId().equals(userId)){
                return JSON.toJSONString(java.util.Map.of("error", "无权限访问该订单"));
            }
            sessionCtx.setCachedOrder(travelOrder);
        }
        String result = JSON.toJSONString(travelOrder != null ? travelOrder : "{\"error\":\"order not found\"}");
        logger.info("[TOOL][query_travel_order] result={}", result);
        return result;
    }

    @Tool(name = "query_travel_order_by_order_id", description = "根据差旅单号，查询指定差旅单详情。")
    public String queryTravelOrderByOrderId(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "order_id", description = "差旅单ID") String orderId) {

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][query_travel_order_by_order_id] userId={}, orderId={}", userId, orderId);

        TravelOrder order = travelOrderRepository.findByOrderId(orderId).orElse(null);
        if (order != null) {
            if(!order.getUserId().equals(userId)){
                return JSON.toJSONString(java.util.Map.of("error", "无权限访问该订单"));
            }
            sessionCtx.setCachedOrder(order);
        }

        String result = JSON.toJSONString(order != null ? order : "{\"error\":\"order not found\"}");
        logger.info("[TOOL][query_travel_order_by_order_id] result={}", result);
        return result;
    }

    @Tool(name = "query_travel_orders", description = "查询用户差旅单列表或指定差旅单详情。支持按状态、出发日期范围过滤。")
    public String queryTravelOrders(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "status", description = "状态过滤，支持单个或逗号分隔的多个状态，如 DRAFT 或 DRAFT,SUBMITTED。可选值: DRAFT/SUBMITTED/APPROVED/REJECTED/COMPLETED/CANCELLED", required = false) String status,
            @ToolParam(name = "start_date", description = "筛选出发日期不早于此日期的差旅单，格式 YYYY-MM-DD，可选", required = false) String startDate,
            @ToolParam(name = "end_date", description = "筛选出发日期不晚于此日期的差旅单，格式 YYYY-MM-DD，可选", required = false) String endDate) {

        String userId = sessionCtx.getUserId();

        List<TravelOrder> orders;
        boolean hasDateFilter = (startDate != null && !startDate.isBlank())
                || (endDate != null && !endDate.isBlank());

        if (hasDateFilter) {
            LambdaQueryWrapper<TravelOrder> wrapper = new LambdaQueryWrapper<TravelOrder>()
                    .eq(TravelOrder::getUserId, userId);
            if (status != null && !status.isBlank()) {
                List<TravelOrderStatus> statusList = parseStatusList(status);
                if (statusList == null) {
                    return JSON.toJSONString(java.util.Map.of("error",
                            "无效的状态参数: " + status + "，可选值: DRAFT/SUBMITTED/APPROVED/REJECTED/COMPLETED/CANCELLED"));
                }
                if (statusList.size() == 1) {
                    wrapper.eq(TravelOrder::getStatus, statusList.get(0));
                } else {
                    wrapper.in(TravelOrder::getStatus, statusList);
                }
            }
            if (startDate != null && !startDate.isBlank()) {
                wrapper.le(TravelOrder::getDepartureDate, endDate != null && !endDate.isBlank() ? endDate : startDate);
            }
            if (endDate != null && !endDate.isBlank()) {
                wrapper.ge(TravelOrder::getReturnDate, startDate);
            }
            wrapper.orderByAsc(TravelOrder::getDepartureDate);
            orders = travelOrderMapper.selectList(wrapper);
        } else if (status != null && !status.isBlank()) {
            List<TravelOrderStatus> statusList = parseStatusList(status);
            if (statusList == null) {
                return JSON.toJSONString(java.util.Map.of("error",
                        "无效的状态参数: " + status + "，可选值: DRAFT/SUBMITTED/APPROVED/REJECTED/COMPLETED/CANCELLED"));
            }
            if (statusList.size() == 1) {
                orders = travelOrderRepository.findByUserIdAndStatus(userId, statusList.get(0));
            } else {
                LambdaQueryWrapper<TravelOrder> wrapper = new LambdaQueryWrapper<TravelOrder>()
                        .eq(TravelOrder::getUserId, userId)
                        .in(TravelOrder::getStatus, statusList)
                        .orderByAsc(TravelOrder::getDepartureDate);
                orders = travelOrderMapper.selectList(wrapper);
            }
        } else {
            orders = travelOrderRepository.findByUserId(userId);
        }

        String result = JSON.toJSONString(orders);
        logger.info("[TOOL][query_travel_orders] result={}", result);
        return result;
    }


    // ─────────────────────────────────────────────────────────────────────────
    // 查询审批状态
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(name = "query_approval_status", description = "查询差旅审批状态。若提供审批实例ID则按ID查询；否则按用户ID查询其最近的审批单")
    public String queryApprovalStatus(AgentSessionContext sessionCtx, @ToolParam(name = "process_instance_id", description = "审批实例ID，可选", required = false) String processInstanceId) {

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][query_approval_status] userId={}, processInstanceId={}", userId, processInstanceId);

        if (isBlank(userId)) {
            return toJson(TravelOrderWriteTools.ErrorResult.of("INVALID_PARAM", "user_id 不能为空"));
        }

        try {
            Optional<ApprovalRecord> recordOpt;
            String queryMode;
            if (!isBlank(processInstanceId)) {
                recordOpt = approvalService.findByProcessInstanceId(processInstanceId);
                queryMode = "by_instance";
            } else {
                recordOpt = approvalService.findLatestByUserId(userId);
                queryMode = "latest";
            }

            if (recordOpt.isEmpty()) {
                return toJson(new TravelOrderWriteTools.QueryApprovalResult(false, queryMode, null, null, null, null, null, null, null, null, "未找到相关审批记录。"));
            }

            ApprovalRecord record = recordOpt.get();
            logger.info("[TOOL][query_approval_status] 查询到审批单 processInstanceId={}, status={}", record.getProcessInstanceId(), record.getStatus());

            return toJson(new TravelOrderWriteTools.QueryApprovalResult(true, queryMode, record.getProcessInstanceId(), record.getOrderId(), record.getUserId(), record.getTitle(), record.getStatus().getCode(), record.getSubmitTime() != null ? record.getSubmitTime().toString() : null, record.getUpdateTime() != null ? record.getUpdateTime().toString() : null, record.getRemark(), null));

        } catch (Exception e) {
            logger.error("[TOOL][query_approval_status] 查询失败 userId={}", userId, e);
            return toJson(TravelOrderWriteTools.ErrorResult.of("INTERNAL_ERROR", "查询审批状态失败：" + e.getMessage()));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 将强类型结果对象序列化为 JSON 字符串
     */
    private static String toJson(Object obj) {
        return JSON.toJSONString(obj);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 行程时间有效性检查
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 检查用户已审批差旅单的日期有效性，用于行程规划前的前置校验。
     *
     * <p>判断逻辑：
     * <ul>
     *   <li>无 APPROVED 差旅单 → {@code valid=true}（可规划，只是还没有审批单）</li>
     *   <li>当前日期 > 返回日期 → 行程已结束，{@code valid=false}</li>
     *   <li>当前日期 >= 出发日期 → 行程已开始，{@code valid=false}</li>
     *   <li>当前日期 < 出发日期 → 行程尚未开始，{@code valid=true}</li>
     * </ul>
     *
     * @param sessionCtx 会话上下文（自动注入）
     * @return JSON：{@code {valid, today, blocked_orders:[{order_id, destination, departure_date, return_date, status}], message}}
     */
    @Tool(name = "check_travel_time_validity",
            description = "检查用户差旅单的行程时间是否仍然有效（未开始/未结束），用于行程规划前的前置校验。" +
                    "返回 valid=true 表示存在可规划的行程，valid=false 表示行程已开始或已结束、无法规划。")
    public String checkTravelTimeValidity(AgentSessionContext sessionCtx, @ToolParam(name = "order_id", description = "差旅单ID") String orderId) {
        String userId = sessionCtx.getUserId();
        LocalDate today = LocalDate.now();

        logger.info("[TOOL][check_travel_time_validity] userId={}, today={}", userId, today);

        TravelOrder travelOrder = sessionCtx.getCachedOrder() == null ? travelOrderRepository.findByOrderId(orderId).orElse(null) : null;

        if (travelOrder != null) {
            sessionCtx.setCachedOrder(travelOrder);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("today", today.toString());
        if (travelOrder == null) {
            result.put("valid", true);
            result.put("blocked_orders", List.of());
            result.put("message", "当前尚不存在差旅单，但是仍可直接规划行程。");
            logger.info("[TOOL][check_travel_time_validity] userId={}, 无 APPROVED 差旅单", userId);
            return JSON.toJSONString(result);
        }

        String blockReason = evaluateBlockReason(travelOrder, today);
        List<Map<String, Object>> blockedOrders = new ArrayList<>();
        if (blockReason != null) {
            Map<String, Object> blocked = new LinkedHashMap<>();
            blocked.put("order_id", travelOrder.getOrderId());
            blocked.put("destination", travelOrder.getDestination());
            blocked.put("departure_date", travelOrder.getDepartureDate());
            blocked.put("return_date", travelOrder.getReturnDate());
            blocked.put("reason", blockReason);
            blockedOrders.add(blocked);
        }

        if (!blockedOrders.isEmpty()) {
            result.put("valid", false);
            result.put("blocked_orders", blockedOrders);
            String firstReason = (String) blockedOrders.get(0).get("reason");
            result.put("message", String.format("检测到%s的差旅单，无法进行规划。请告知用户并停止规划。", firstReason));
            logger.info("[TOOL][check_travel_time_validity] userId={}, 检测到 {} 个过期差旅单",
                    userId, blockedOrders.size());
        } else {
            result.put("valid", true);
            result.put("blocked_orders", List.of());
            result.put("message", "差旅单日期均有效，可继续规划。");
            logger.info("[TOOL][check_travel_time_validity] userId={}, 所有 APPROVED 差旅单日期有效", userId);
        }

        return JSON.toJSONString(result);
    }

    /**
     * 评估差旅单是否因时间原因被拦截。
     *
     * @return 拦截原因描述，null 表示日期有效不拦截
     */
    private String evaluateBlockReason(TravelOrder order, LocalDate today) {
        try {
            if (order.getReturnDate() != null) {
                LocalDate returnDate = LocalDate.parse(order.getReturnDate());
                if (today.isAfter(returnDate)) {
                    return String.format("行程已结束（返回日期 %s 已过）", order.getReturnDate());
                }
            }
            if (order.getDepartureDate() != null) {
                LocalDate departureDate = LocalDate.parse(order.getDepartureDate());
                if (!today.isBefore(departureDate)) {
                    return String.format("行程已开始（出发日期 %s 已到达或已过）", order.getDepartureDate());
                }
            }
        } catch (DateTimeParseException e) {
            logger.debug("[TOOL][check_travel_time_validity] 日期解析失败: orderId={}, error={}", order.getOrderId(), e.getMessage());
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 审批状态检查（供 BookingAgent 使用）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 检查用户是否存在已审批通过的差旅单，用于预订执行前的前置校验。
     *
     * <p>判断逻辑：
     * <ul>
     *   <li>存在 APPROVED 差旅单 → {@code approved=true}，返回第一条审批单信息</li>
     *   <li>不存在 APPROVED 差旅单 → {@code approved=false}，拒绝预订</li>
     * </ul>
     *
     * @param sessionCtx 会话上下文（自动注入）
     * @return JSON：{@code {approved, order_id, destination, departure_date, return_date, message}}
     */
    @Tool(name = "check_travel_order_approval",
            description = "检查用户是否存在已审批通过的差旅单，用于预订执行前的前置校验。" +
                    "返回 approved=true 表示存在已审批差旅单、可以执行预订，approved=false 表示无审批单、应拒绝预订。")
    public String checkTravelOrderApproval(AgentSessionContext sessionCtx, @ToolParam(name = "order_id", description = "差旅单ID") String orderId) {
        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][check_travel_order_approval] userId={}", userId);

        // 优先复用已缓存的 APPROVED 差旅单
        TravelOrder travelOrder = sessionCtx.getCachedOrder() == null ? travelOrderRepository.findByOrderId(orderId).orElse(null) : null;

        if (travelOrder != null) {
            sessionCtx.setCachedOrder(travelOrder);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (travelOrder == null) {
            result.put("approved", false);
            result.put("message", "未查询到您需要预订的差旅单，请提供一个差旅单号或者告知我出发时间、出发地、目的地等信息。");
            return JSON.toJSONString(result);
        }

        if (travelOrder.getStatus() != TravelOrderStatus.APPROVED) {
            result.put("approved", false);
            result.put("message", "您想要预订的差旅尚未完成审批，请先联系管理员审批通过。或者换一个新的差旅行程帮您做预订。");
            return JSON.toJSONString(result);
        }

        result.put("approved", true);
        result.put("order_id", travelOrder.getOrderId());
        result.put("destination", travelOrder.getDestination());
        result.put("departure_date", travelOrder.getDepartureDate());
        result.put("return_date", travelOrder.getReturnDate());
        result.put("message", String.format("存在已审批差旅单（%s → %s），可以执行预订。",
                travelOrder.getDepartureCity(), travelOrder.getDestination()));
        logger.info("[TOOL][check_travel_order_approval] userId={}, 找到 APPROVED 差旅单 orderId={}",
                userId, travelOrder.getOrderId());

        return JSON.toJSONString(result);
    }

    /**
     * 解析状态参数，支持单个状态或逗号分隔的多个状态。
     * 返回 null 表示存在无效状态值。
     */
    private List<TravelOrderStatus> parseStatusList(String status) {
        String[] parts = status.split(",");
        List<TravelOrderStatus> list = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            TravelOrderStatus s = TravelOrderStatus.of(trimmed);
            if (s == null) {
                return null;
            }
            list.add(s);
        }
        return list.isEmpty() ? null : list;
    }
}
