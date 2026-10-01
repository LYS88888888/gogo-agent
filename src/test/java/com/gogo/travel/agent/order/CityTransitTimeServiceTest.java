package com.gogo.travel.agent.order;

import com.gogo.travel.agent.tools.model.CityTransitTimeService;
import com.gogo.travel.config.TravelPolicyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CityTransitTimeService 单元测试：
 * 显式 city-pair 双向查找、城市归一化（"市"后缀）、分层推断兜底、非法配置忽略、空入参兜底。
 *
 */
class CityTransitTimeServiceTest {

    private CityTransitTimeService service;

    @BeforeEach
    void setUp() {
        TravelPolicyProperties props = new TravelPolicyProperties();
        props.setTier1Cities(List.of("北京", "上海", "广州", "深圳"));
        props.setNewTier1Cities(List.of("成都", "杭州"));
        props.setTier2Cities(List.of("济南"));
        Map<String, Integer> pairs = new HashMap<>();
        pairs.put("北京-上海", 300);
        pairs.put("上海市-苏州", 90);      // 配置里带"市"后缀也应归一化后命中
        pairs.put("bad-key-三段", 100);    // 非法：拆出 3 段，应被忽略
        pairs.put("北京-广州", -5);        // 非法：非正分钟数，应被忽略
        props.setCityPairMinutes(pairs);

        service = new CityTransitTimeService();
        ReflectionTestUtils.setField(service, "policyProperties", props);
        service.init();
    }

    @Test
    void sameCity_returnsZero_withCitySuffixNormalized() {
        assertEquals(0, service.estimateMinutes("北京", "北京市"));
    }

    @Test
    void explicitPair_hitInBothDirections() {
        // 配置只写了"北京-上海"一个方向，两个方向都应命中
        assertEquals(300, service.estimateMinutes("北京", "上海"));
        assertEquals(300, service.estimateMinutes("上海", "北京"));
    }

    @Test
    void explicitPair_lookupNormalizesCitySuffix() {
        assertEquals(90, service.estimateMinutes("苏州市", "上海市"));
    }

    @Test
    void invalidPairEntries_ignoredAndFallBackToTier() {
        // "北京-广州" 配置值非法被忽略 → 走一线互达分层推断 270
        assertEquals(270, service.estimateMinutes("北京", "广州"));
    }

    @Test
    void tierFallback_tier1WithNewTier1() {
        assertEquals(270, service.estimateMinutes("深圳", "杭州"));
    }

    @Test
    void tierFallback_containsTier2() {
        assertEquals(300, service.estimateMinutes("北京", "济南"));
    }

    @Test
    void tierFallback_containsUnknownCity() {
        assertEquals(360, service.estimateMinutes("北京", "三亚"));
    }

    @Test
    void blankInput_returnsConservativeDefault() {
        assertEquals(240, service.estimateMinutes(null, "北京"));
        assertEquals(240, service.estimateMinutes("北京", "  "));
    }
}
