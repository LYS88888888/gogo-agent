package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.tools.model.IdTypeLabels;
import com.gogo.travel.business.user.entity.UserProfile;
import com.gogo.travel.business.user.repo.UserProfileRepository;
import io.agentscope.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 用户信息读工具集：查询联系/乘机人信息与常驻城市，
 * 用于酒店/机票预订时自动填充乘客与联系人字段、推断出发城市。
 * 写操作见 {@link UserInfoWriteTools}，与差旅单/预订工具的 Read/Write 分离惯例保持一致。
 *
 */
@Component
public class UserInfoReadTools {

    private static final Logger logger = LoggerFactory.getLogger(UserInfoReadTools.class);

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Tool(name = "query_user_contact_info",
          description = "查询用户的联系信息和乘机人信息，包括姓名拼音、邮箱、中文姓名、证件类型、证件号码、手机号、性别。"
                      + "用于酒店/机票预订时自动填充联系人和乘客信息。"
                      + "若档案中缺少相关信息，返回提示让 Agent 向用户追问。")
    public String queryUserContactInfo(AgentSessionContext sessionCtx) {
        String cached = sessionCtx.getUserContactInfo();
        if (cached != null) {
            logger.info("[TOOL][query_user_contact_info] hit cache, userId={}", sessionCtx.getUserId());
            return cached;
        }

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][query_user_contact_info] miss cache, userId={}", userId);

        Optional<UserProfile> optProfile = userProfileRepository.findByUserId(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);

        if (optProfile.isPresent()) {
            UserProfile profile = optProfile.get();
            String namePinyin = profile.getNamePinyin();
            String email = profile.getEmail();
            String chineseName = profile.getChineseName();
            Integer idType = profile.getIdType();
            String idNumber = profile.getIdNumber();
            String phone = profile.getPhone();
            String gender = profile.getGender();

            boolean hasNamePinyin = namePinyin != null && !namePinyin.isBlank();
            boolean hasEmail = email != null && !email.isBlank();
            boolean hasChineseName = chineseName != null && !chineseName.isBlank();
            boolean hasIdType = idType != null;
            boolean hasIdNumber = idNumber != null && !idNumber.isBlank();
            boolean hasPhone = phone != null && !phone.isBlank();
            boolean hasGender = gender != null && !gender.isBlank();

            // 酒店联系人信息（姓名拼音 + 邮箱）
            if (hasNamePinyin) {
                String[] parts = namePinyin.trim().split("\\s+", 2);
                result.put("lastName", parts[0]);
                result.put("firstName", parts.length > 1 ? parts[1] : "");
                result.put("namePinyin", namePinyin);
            }
            if (hasEmail) {
                result.put("email", email);
            }

            // 机票乘客信息（中文姓名 + 证件 + 手机 + 性别）
            if (hasChineseName) {
                result.put("chineseName", chineseName);
            }
            if (hasIdType) {
                result.put("idType", idType);
                result.put("idTypeLabel", IdTypeLabels.LABELS.getOrDefault(idType, "未知"));
            }
            if (hasIdNumber) {
                result.put("idNumber", idNumber);
            }
            if (hasPhone) {
                result.put("phone", phone);
            }
            if (hasGender) {
                result.put("gender", gender);
            }

            // 酒店联系人完整性
            boolean hotelComplete = hasNamePinyin && hasEmail;
            result.put("hotelComplete", hotelComplete);

            // 机票乘客信息完整性
            boolean flightComplete = hasChineseName && hasIdType && hasIdNumber && hasPhone && hasGender;
            result.put("flightComplete", flightComplete);

            // 综合完整性
            result.put("complete", hotelComplete && flightComplete);

            if (!hotelComplete || !flightComplete) {
                StringBuilder hint = new StringBuilder("用户档案中缺少以下信息，请向用户追问：");
                if (!hasNamePinyin) {
                    hint.append("姓名拼音（如 ZHANG SAN）；");
                }
                if (!hasEmail) {
                    hint.append("邮箱地址；");
                }
                if (!hasChineseName) {
                    hint.append("中文姓名（如 张三）；");
                }
                if (!hasIdType) {
                    hint.append("证件类型（身份证/护照/军官证等，0-身份证 1-护照 2-其他 3-回乡证 4-军官证 5-警官证 6-港澳通行证 7-台胞证 8-台湾通行证 9-外国人永久居留身份证）；");
                }
                if (!hasIdNumber) {
                    hint.append("证件号码（身份证/护照号）；");
                }
                if (!hasPhone) {
                    hint.append("手机号；");
                }
                if (!hasGender) {
                    hint.append("性别（男/女）；");
                }
                result.put("message", hint.toString());
            }
        } else {
            result.put("complete", false);
            result.put("hotelComplete", false);
            result.put("flightComplete", false);
            result.put("message", "未找到用户档案，请向用户询问姓名拼音、邮箱、中文姓名、证件类型、证件号码、手机号、性别");
        }

        String json = JSON.toJSONString(result);
        sessionCtx.setUserContactInfo(json);
        logger.info("[TOOL][query_user_contact_info] result={}", json);
        return json;
    }

    /**
     * 查询用户常驻/办公城市，用于在用户未提供出发地时推断出发城市。
     */
    @Tool(name = "query_user_base_location", description = "查询用户常驻/办公城市，用于在用户未提供出发地时推断出发城市")
    public String queryUserBaseLocation(AgentSessionContext sessionCtx) {
        String cached = sessionCtx.getUserBaseLocation();
        if (cached != null) {
            logger.info("[TOOL][query_user_base_location] hit cache, userId={}", sessionCtx.getUserId());
            return cached;
        }

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][query_user_base_location] miss cache, userId={}", userId);

        Optional<UserProfile> profile = userProfileRepository.findByUserId(userId);
        String result;
        if (profile.isPresent()) {
            String baseCity = profile.get().getBaseCity();
            if (baseCity != null && !baseCity.isBlank()) {
                result = JSON.toJSONString(Map.of("userId", userId, "baseCity", baseCity));
            } else {
                // 用户存在但未配置常驻城市，提示 Agent 向用户追问出发地
                result = JSON.toJSONString(Map.of("userId", userId, "baseCity", "",
                        "message", "未查询到用户的常驻城市，请向用户询问出发城市"));
            }
        } else {
            // 用户档案不存在，同样提示追问
            result = JSON.toJSONString(Map.of("userId", userId, "baseCity", "",
                    "message", "未找到用户档案，请向用户询问出发城市"));
        }
        sessionCtx.setUserBaseLocation(result);
        logger.info("[TOOL][query_user_base_location] result={}", result);
        return result;
    }
}
