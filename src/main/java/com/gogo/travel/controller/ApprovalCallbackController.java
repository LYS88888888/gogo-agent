package com.gogo.travel.controller;

import com.gogo.travel.business.order.service.TravelOrderService;
import com.gogo.travel.controller.request.DingTalkCallbackEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * todo 待完善
 */
@RestController
@RequestMapping("/api/callback")
public class ApprovalCallbackController {

    @Autowired
    private TravelOrderService travelOrderService;

    @PostMapping("/dingtalk/approval")
    public String onApprovalEvent(@RequestBody DingTalkCallbackEvent event) {
        String instanceId = event.getProcessInstanceId();
        String result = event.getResult();
        boolean agree = "agree".equalsIgnoreCase(result);

        // 审批决策 + 同步差旅单状态，由事务保证原子性（与管理员审批接口共用，非待审批状态幂等跳过）
        // 审批结果不主动推送给 Agent 会话：用户通过“我的差旅”页或对话中查询审批状态获知结果
        travelOrderService.decideAndSyncOrder(instanceId, agree, event.getRemark());

        return "success";
    }
}
