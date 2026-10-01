package com.gogo.travel.business.policy.service;

import com.gogo.travel.business.policy.entity.PolicyCheckResult;
import com.gogo.travel.business.policy.entity.TravelPolicy;
import com.gogo.travel.business.policy.entity.TravelPolicyRule;
import com.gogo.travel.business.policy.repo.TravelPolicyRuleRepository;
import com.gogo.travel.business.user.entity.UserProfile;
import com.gogo.travel.business.user.repo.UserProfileRepository;
import com.gogo.travel.config.TravelPolicyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link TravelPolicyService} 单元测试：
 * getPolicy 的职级×城市等级路由（命中 / 规则缺失 / 二线折叠为"其他"档）+
 * checkCompliance 三种订单类型的合规判定与金额守卫。
 * 仓储层用 Mockito mock，城市分级用真实 {@link TravelPolicyProperties} 灌配置。
 *
 */
@DisplayName("差旅政策服务单元测试")
class TravelPolicyServiceTest {

    private TravelPolicyService service;
    private UserProfileRepository userProfileRepository;
    private TravelPolicyRuleRepository ruleRepository;

    @BeforeEach
    void setUp() {
        userProfileRepository = Mockito.mock(UserProfileRepository.class);
        ruleRepository = Mockito.mock(TravelPolicyRuleRepository.class);

        TravelPolicyProperties props = new TravelPolicyProperties();
        props.setTier1Cities(List.of("北京", "上海", "广州", "深圳"));
        props.setNewTier1Cities(List.of("成都", "杭州"));
        props.setTier2Cities(List.of("济南"));

        service = new TravelPolicyService();
        ReflectionTestUtils.setField(service, "userProfileRepository", userProfileRepository);
        ReflectionTestUtils.setField(service, "travelPolicyProperties", props);
        ReflectionTestUtils.setField(service, "travelPolicyRuleRepository", ruleRepository);
    }

    // -------------------------------------------------------------------------
    // getPolicy
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("P7 + 新一线城市：命中规则并完整回填政策字段")
    void getPolicy_p7NewTier1_hitsRule() {
        stubUserLevel("u1", "P7");
        when(ruleRepository.findByLevelAndCityTier(7, "新一线")).thenReturn(Optional.of(sampleRule()));

        TravelPolicy policy = service.getPolicy("u1", "杭州");

        assertEquals("P7", policy.getUserLevel());
        assertEquals("新一线", policy.getCityTier());
        assertEquals("杭州", policy.getDestinationCity());
        assertEquals("经济舱/商务舱", policy.getFlightClass());
        assertEquals("一等座", policy.getTrainSeatClass());
        assertEquals(600.0, policy.getHotelLimit());
        assertEquals(4, policy.getHotelStarLimit());
    }

    @Test
    @DisplayName("P0 + 一线城市：规则表未配置时抛出明确异常")
    void getPolicy_p0Tier1_ruleNotFound() {
        stubUserLevel("u2", "P0");
        when(ruleRepository.findByLevelAndCityTier(anyInt(), anyString())).thenReturn(Optional.empty());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.getPolicy("u2", "北京"));
        assertTrue(e.getMessage().contains("未找到匹配的差旅政策规则"), "异常信息应说明规则缺失：" + e.getMessage());
        assertTrue(e.getMessage().contains("一线"), "异常信息应包含城市等级：" + e.getMessage());
    }

    @Test
    @DisplayName("二线城市：政策按'其他'档查询，但 DTO 保留原始'二线'分级")
    void getPolicy_tier2City_collapsesToOtherPolicyTier() {
        stubUserLevel("u3", "P7");
        // 政策规则表只有 一线/新一线/其他 3 档，二线应折叠为"其他"去查
        when(ruleRepository.findByLevelAndCityTier(7, "其他")).thenReturn(Optional.of(sampleRule()));

        TravelPolicy policy = service.getPolicy("u3", "济南");

        assertEquals("二线", policy.getCityTier());
        assertEquals("济南", policy.getDestinationCity());
    }

    // -------------------------------------------------------------------------
    // checkCompliance
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("HOTEL：超上限判超标，限额内判合规")
    void checkCompliance_hotelAmountAgainstLimit() {
        TravelPolicy policy = samplePolicy();

        PolicyCheckResult over = service.checkCompliance("{\"type\":\"HOTEL\",\"amount\":800}", policy);
        assertFalse(over.isCompliant());
        assertTrue(over.getViolations().get(0).contains("酒店金额超出政策上限"));
        assertFalse(over.getSuggestions().isEmpty());

        PolicyCheckResult within = service.checkCompliance("{\"type\":\"HOTEL\",\"amount\":450}", policy);
        assertTrue(within.isCompliant());
        assertTrue(within.getViolations().isEmpty());
    }

    @Test
    @DisplayName("HOTEL：amount 缺失或非正数时拦下，不得静默判合规")
    void checkCompliance_hotelInvalidAmount_rejected() {
        TravelPolicy policy = samplePolicy();

        // LLM 漏传 amount → getDoubleValue 返回 0，守卫应拦下而非放行
        PolicyCheckResult missing = service.checkCompliance("{\"type\":\"HOTEL\"}", policy);
        assertFalse(missing.isCompliant());
        assertTrue(missing.getViolations().get(0).contains("金额缺失或无效"));

        PolicyCheckResult negative = service.checkCompliance("{\"type\":\"HOTEL\",\"amount\":-100}", policy);
        assertFalse(negative.isCompliant());
    }

    @Test
    @DisplayName("FLIGHT：舱位不在允许范围判超标，允许范围内判合规")
    void checkCompliance_flightClass() {
        TravelPolicy policy = samplePolicy();

        PolicyCheckResult over = service.checkCompliance(
                "{\"type\":\"FLIGHT\",\"flightClass\":\"商务舱\"}", policy);
        assertFalse(over.isCompliant());
        assertTrue(over.getViolations().get(0).contains("机票舱位超出政策标准"));

        PolicyCheckResult ok = service.checkCompliance(
                "{\"type\":\"FLIGHT\",\"flightClass\":\"经济舱\"}", policy);
        assertTrue(ok.isCompliant());
    }

    @Test
    @DisplayName("TRAIN：座位等级不匹配判超标")
    void checkCompliance_trainSeatClass() {
        TravelPolicy policy = samplePolicy();

        PolicyCheckResult over = service.checkCompliance(
                "{\"type\":\"TRAIN\",\"seatClass\":\"商务座\"}", policy);
        assertFalse(over.isCompliant());
        assertTrue(over.getViolations().get(0).contains("火车座位超出政策标准"));

        PolicyCheckResult ok = service.checkCompliance(
                "{\"type\":\"TRAIN\",\"seatClass\":\"二等座\"}", policy);
        assertTrue(ok.isCompliant());
    }

    @Test
    @DisplayName("TRAIN：实际座位低于政策允许的最高等级判合规")
    void checkCompliance_trainSeatClassBelowMax_compliant() {
        // 政策允许一等座（等级 2），实际二等座（等级 1），应判合规
        TravelPolicy policy = new TravelPolicy();
        policy.setTrainSeatClass("一等座");

        PolicyCheckResult ok = service.checkCompliance(
                "{\"type\":\"TRAIN\",\"seatClass\":\"二等座\"}", policy);
        assertTrue(ok.isCompliant());
    }

    @Test
    @DisplayName("FLIGHT：实际舱位低于政策允许的最高等级判合规")
    void checkCompliance_flightClassBelowMax_compliant() {
        // 政策允许商务舱（等级 2），实际经济舱（等级 1），应判合规
        TravelPolicy policy = new TravelPolicy();
        policy.setFlightClass("商务舱");

        PolicyCheckResult ok = service.checkCompliance(
                "{\"type\":\"FLIGHT\",\"flightClass\":\"经济舱\"}", policy);
        assertTrue(ok.isCompliant());
    }

    @Test
    @DisplayName("订单摘要为 null JSON 时兜底返回合规（不抛异常）")
    void checkCompliance_nullOrder_defaultsCompliant() {
        PolicyCheckResult result = service.checkCompliance("null", samplePolicy());
        assertTrue(result.isCompliant());
    }

    // -------------------------------------------------------------------------
    // 测试数据
    // -------------------------------------------------------------------------

    private void stubUserLevel(String userId, String level) {
        when(userProfileRepository.findByUserId(userId))
                .thenReturn(Optional.of(new UserProfile(userId, "北京", level)));
    }

    private static TravelPolicyRule sampleRule() {
        TravelPolicyRule rule = new TravelPolicyRule();
        rule.setFlightClass("经济舱/商务舱");
        rule.setTrainSeatClass("一等座");
        rule.setHotelLimit(600.0);
        rule.setHotelStarLimit(4);
        rule.setDailyMealLimit(150.0);
        rule.setDailyTransportLimit(100.0);
        rule.setApprovalThreshold(3000.0);
        rule.setAdvanceBookingDays(3);
        return rule;
    }

    /** checkCompliance 是纯函数，直接构造 policy 即可，无需走 mock 仓储。 */
    private static TravelPolicy samplePolicy() {
        TravelPolicy policy = new TravelPolicy();
        policy.setFlightClass("经济舱");
        policy.setTrainSeatClass("二等座");
        policy.setHotelLimit(500.0);
        return policy;
    }
}
