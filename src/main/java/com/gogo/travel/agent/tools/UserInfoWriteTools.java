package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.tools.model.IdTypeLabels;
import com.gogo.travel.business.user.entity.UserProfile;
import com.gogo.travel.business.user.repo.UserProfileRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 用户信息写工具集：更新联系/乘机人信息与常驻城市。
 * 读操作见 {@link UserInfoReadTools}，与差旅单/预订工具的 Read/Write 分离惯例保持一致。
 *
 * <p>更新入口对证件类型/证件号/手机号/性别做格式校验，拦截 LLM 传入的非法值并提示其向用户重新确认；
 * 用户档案不存在时自动创建，避免"查询提示追问、更新却无法写入"的死锁。
 *
 */
@Component
public class UserInfoWriteTools {

    private static final Logger logger = LoggerFactory.getLogger(UserInfoWriteTools.class);

    /** 中国大陆手机号 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    /** 18 位身份证号（末位可为 X/x） */
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("^\\d{17}[\\dXx]$");
    /** 护照号：5~20 位字母数字 */
    private static final Pattern PASSPORT_PATTERN = Pattern.compile("^[A-Za-z0-9]{5,20}$");
    /** 其他证件：各类证件格式差异大，仅做 4~30 位无空白字符的粗校验，避免过度拦截 */
    private static final Pattern GENERIC_ID_PATTERN = Pattern.compile("^\\S{4,30}$");

    @Autowired
    private UserProfileRepository userProfileRepository;

    /**
     * 更新用户的联系信息和乘机人信息。用户主动提供新信息时调用，自动保存到档案。
     */
    @Tool(name = "update_user_contact_info",
          description = "更新用户的联系信息和乘机人信息。支持以下字段："
                      + "name_pinyin（姓名拼音，格式：大写姓在前名在后空格分隔，如 ZHANG SAN）、"
                      + "email（邮箱）、"
                      + "chinese_name（中文姓名，如 张三）、"
                      + "id_type（证件类型：0-身份证 1-护照 2-其他 3-回乡证 4-军官证 5-警官证 6-港澳通行证 7-台胞证 8-台湾通行证 9-外国人永久居留身份证）、"
                      + "id_number（证件号码，身份证/护照号）、"
                      + "phone（手机号）、"
                      + "gender（性别，M-男 F-女）。"
                      + "当用户主动提供或修正了以上任意信息时调用，至少提供一个字段。"
                      + "用户档案不存在时会自动创建。字段格式校验不通过时返回 success=false，需向用户重新确认后再调用。")
    public String updateUserContactInfo(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "name_pinyin",
                       description = "姓名拼音（大写，姓在前名在后，空格分隔），如 ZHANG SAN。不更新时传 null。",
                       required = false) String namePinyin,
            @ToolParam(name = "email",
                       description = "用户邮箱地址。不更新时传 null。",
                       required = false) String email,
            @ToolParam(name = "chinese_name",
                       description = "中文姓名，如 张三。不更新时传 null。",
                       required = false) String chineseName,
            @ToolParam(name = "id_type",
                       description = "证件类型：0-身份证 1-护照 2-其他 3-回乡证 4-军官证 5-警官证 6-港澳通行证 7-台胞证 8-台湾通行证 9-外国人永久居留身份证。不更新时传 null。",
                       required = false) Integer idType,
            @ToolParam(name = "id_number",
                       description = "证件号码（身份证/护照号）。不更新时传 null。",
                       required = false) String idNumber,
            @ToolParam(name = "phone",
                       description = "手机号。不更新时传 null。",
                       required = false) String phone,
            @ToolParam(name = "gender",
                       description = "性别：M-男，F-女。不更新时传 null。",
                       required = false) String gender) {

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][update_user_contact_info] userId={}, namePinyin={}, email={}, chineseName={}, idType={}, idNumber={}, phone={}, gender={}",
                userId, namePinyin, email, chineseName, idType, idNumber, phone, gender);

        // 至少需要一个字段
        boolean hasNamePinyin = namePinyin != null && !namePinyin.isBlank();
        boolean hasEmail = email != null && !email.isBlank();
        boolean hasChineseName = chineseName != null && !chineseName.isBlank();
        boolean hasIdType = idType != null;
        boolean hasIdNumber = idNumber != null && !idNumber.isBlank();
        boolean hasPhone = phone != null && !phone.isBlank();
        boolean hasGender = gender != null && !gender.isBlank();

        if (!hasNamePinyin && !hasEmail && !hasChineseName && !hasIdType && !hasIdNumber && !hasPhone && !hasGender) {
            return JSON.toJSONString(Map.of("success", false, "message",
                    "至少需要提供其中一个字段：name_pinyin / email / chinese_name / id_type / id_number / phone / gender"));
        }

        // ── 格式校验：拦截非法值，返回错误让 LLM 向用户重新确认，避免脏数据导致下游下单失败 ──
        List<String> errors = new ArrayList<>();
        if (hasIdType && !IdTypeLabels.LABELS.containsKey(idType)) {
            errors.add("id_type=" + idType + " 不是有效的证件类型代码（有效值 0-9）");
        }
        if (hasPhone && !PHONE_PATTERN.matcher(phone.trim()).matches()) {
            errors.add("phone=" + phone + " 不是有效的 11 位大陆手机号");
        }
        String normalizedGender = null;
        if (hasGender) {
            normalizedGender = normalizeGender(gender.trim());
            if (normalizedGender == null) {
                errors.add("gender=" + gender + " 无法识别，请使用 M/男 或 F/女");
            }
        }

        // 档案不存在时自动创建（save 底层为 insert-or-update），
        // 避免"查询提示追问、收齐信息后更新却无法写入"的死锁
        Optional<UserProfile> optProfile = userProfileRepository.findByUserId(userId);
        boolean created = optProfile.isEmpty();
        UserProfile profile = optProfile.orElseGet(() -> new UserProfile(userId, null));

        // 证件号按证件类型分发校验：优先用本次传入的 id_type，未传则用档案已有值
        Integer effectiveIdType = hasIdType ? idType : profile.getIdType();
        if (hasIdNumber) {
            String idError = validateIdNumber(effectiveIdType, idNumber.trim());
            if (idError != null) {
                errors.add(idError);
            }
        }

        if (!errors.isEmpty()) {
            logger.info("[TOOL][update_user_contact_info] 字段校验未通过, userId={}, errors={}", userId, errors);
            return JSON.toJSONString(Map.of("success", false,
                    "message", "字段格式校验未通过，请向用户重新确认后再更新：" + String.join("；", errors)));
        }

        List<String> updatedFields = new ArrayList<>();

        if (hasNamePinyin) {
            profile.setNamePinyin(namePinyin.trim().toUpperCase());
            updatedFields.add("name_pinyin");
        }
        if (hasEmail) {
            profile.setEmail(email.trim());
            updatedFields.add("email");
        }
        if (hasChineseName) {
            profile.setChineseName(chineseName.trim());
            updatedFields.add("chinese_name");
        }
        if (hasIdType) {
            profile.setIdType(idType);
            updatedFields.add("id_type");
        }
        if (hasIdNumber) {
            String idNo = idNumber.trim();
            // 身份证末位 x 统一存为大写 X
            if (effectiveIdType != null && effectiveIdType == 0) {
                idNo = idNo.toUpperCase();
            }
            profile.setIdNumber(idNo);
            updatedFields.add("id_number");
        }
        if (hasPhone) {
            profile.setPhone(phone.trim());
            updatedFields.add("phone");
        }
        if (hasGender) {
            // 已在前置校验中归一化为 M/F
            profile.setGender(normalizedGender);
            updatedFields.add("gender");
        }

        userProfileRepository.save(profile);
        // 档案已变更，失效会话内的联系信息缓存，下次查询回源数据库
        sessionCtx.invalidateUserContactInfo();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("userId", userId);
        result.put("updatedFields", updatedFields);
        if (created) {
            result.put("created", true);
        }
        if (hasNamePinyin) {
            result.put("namePinyin", profile.getNamePinyin());
        }
        if (hasEmail) {
            result.put("email", profile.getEmail());
        }
        if (hasChineseName) {
            result.put("chineseName", profile.getChineseName());
        }
        if (hasIdType) {
            result.put("idType", profile.getIdType());
            result.put("idTypeLabel", IdTypeLabels.LABELS.getOrDefault(profile.getIdType(), "未知"));
        }
        if (hasIdNumber) {
            result.put("idNumber", profile.getIdNumber());
        }
        if (hasPhone) {
            result.put("phone", profile.getPhone());
        }
        if (hasGender) {
            result.put("gender", profile.getGender());
        }

        String json = JSON.toJSONString(result);
        logger.info("[TOOL][update_user_contact_info] result={}", json);
        return json;
    }

    /**
     * 更新用户常驻/办公城市。用户告知常驻城市变化（搬家、更换工作城市）时调用。
     */
    @Tool(name = "update_user_base_location",
          description = "更新用户的常驻/办公城市。当用户告知常驻城市发生变化（如搬家、更换工作城市）时调用，后续推断出发地将使用新城市。")
    public String updateUserBaseLocation(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "base_city", description = "常驻城市名，如 北京、深圳") String baseCity) {

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][update_user_base_location] userId={}, baseCity={}", userId, baseCity);

        if (baseCity == null || baseCity.isBlank()) {
            return JSON.toJSONString(Map.of("success", false, "message", "base_city 不能为空，请向用户确认常驻城市"));
        }
        String city = baseCity.trim();
        if (city.length() > 30) {
            return JSON.toJSONString(Map.of("success", false, "message", "base_city 过长，请向用户确认常驻城市名称"));
        }

        // 档案不存在时自动创建，与 update_user_contact_info 行为一致
        Optional<UserProfile> optProfile = userProfileRepository.findByUserId(userId);
        boolean created = optProfile.isEmpty();
        UserProfile profile = optProfile.orElseGet(() -> new UserProfile(userId, null));
        profile.setBaseCity(city);
        userProfileRepository.save(profile);
        // 常驻城市已变更，失效会话内缓存，下次查询回源数据库
        sessionCtx.invalidateUserBaseLocation();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("userId", userId);
        result.put("baseCity", city);
        if (created) {
            result.put("created", true);
        }
        String json = JSON.toJSONString(result);
        logger.info("[TOOL][update_user_base_location] result={}", json);
        return json;
    }

    /**
     * 按证件类型校验证件号格式；通过返回 null，不通过返回错误描述。
     * 仅对身份证/护照做强校验，其余证件类型（回乡证/军官证等）格式差异大，只做粗校验。
     */
    private String validateIdNumber(Integer idType, String idNumber) {
        if (idType != null && idType == 0) {
            return ID_CARD_PATTERN.matcher(idNumber).matches() ? null
                    : "id_number=" + idNumber + " 不是有效的 18 位身份证号";
        }
        if (idType != null && idType == 1) {
            return PASSPORT_PATTERN.matcher(idNumber).matches() ? null
                    : "id_number=" + idNumber + " 不是有效的护照号（5-20 位字母或数字）";
        }
        return GENERIC_ID_PATTERN.matcher(idNumber).matches() ? null
                : "id_number=" + idNumber + " 格式异常（应为 4-30 位无空白字符）";
    }

    /**
     * 将性别输入统一为 M/F；无法识别时返回 null，由调用方返回校验错误。
     */
    private String normalizeGender(String input) {
        if (input == null) {
            return null;
        }
        String upper = input.toUpperCase();
        if ("M".equals(upper) || "男".equals(input) || "MALE".equals(upper)) {
            return "M";
        }
        if ("F".equals(upper) || "女".equals(input) || "FEMALE".equals(upper)) {
            return "F";
        }
        // 无法识别的输入不再原样透传，交由校验拦截
        return null;
    }
}
