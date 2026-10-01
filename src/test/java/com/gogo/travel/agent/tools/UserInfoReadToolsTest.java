package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.business.user.entity.UserProfile;
import com.gogo.travel.business.user.repo.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * UserInfoReadTools 单元测试：query_user_contact_info 的完整性判断边界与会话缓存命中。
 *
 */
@ExtendWith(MockitoExtension.class)
class UserInfoReadToolsTest {

    private static final String USER_ID = "u_test";

    @Mock
    private UserProfileRepository userProfileRepository;

    @InjectMocks
    private UserInfoReadTools tools;

    private AgentSessionContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new AgentSessionContext(USER_ID, "s_test");
    }

    private UserProfile existingProfile() {
        return new UserProfile(USER_ID, "北京");
    }

    private void stubProfile(UserProfile profile) {
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
    }

    @Test
    void query_onlyNamePinyin_hotelIncomplete() {
        UserProfile profile = existingProfile();
        profile.setNamePinyin("ZHANG SAN");
        stubProfile(profile);

        JSONObject o = JSON.parseObject(tools.queryUserContactInfo(ctx));

        assertEquals("ZHANG", o.getString("lastName"));
        assertEquals("SAN", o.getString("firstName"));
        assertFalse(o.getBooleanValue("hotelComplete"));
        assertFalse(o.getBooleanValue("complete"));
        assertTrue(o.getString("message").contains("邮箱"));
    }

    @Test
    void query_idTypeWithoutIdNumber_flightIncomplete() {
        UserProfile profile = existingProfile();
        profile.setChineseName("张三");
        profile.setIdType(0);
        profile.setIdNumber(" ");
        profile.setPhone("13812345678");
        profile.setGender("M");
        stubProfile(profile);

        JSONObject o = JSON.parseObject(tools.queryUserContactInfo(ctx));

        assertFalse(o.getBooleanValue("flightComplete"));
        assertTrue(o.getString("message").contains("证件号码"));
    }

    @Test
    void query_fullProfile_complete() {
        UserProfile profile = existingProfile();
        profile.setNamePinyin("ZHANG SAN");
        profile.setEmail("zhangsan@example.com");
        profile.setChineseName("张三");
        profile.setIdType(0);
        profile.setIdNumber("11010119900307761X");
        profile.setPhone("13812345678");
        profile.setGender("M");
        stubProfile(profile);

        JSONObject o = JSON.parseObject(tools.queryUserContactInfo(ctx));

        assertTrue(o.getBooleanValue("hotelComplete"));
        assertTrue(o.getBooleanValue("flightComplete"));
        assertTrue(o.getBooleanValue("complete"));
        assertNull(o.getString("message"));
    }

    @Test
    void query_profileAbsent_promptsCollection() {
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        JSONObject o = JSON.parseObject(tools.queryUserContactInfo(ctx));

        assertFalse(o.getBooleanValue("complete"));
        assertTrue(o.getString("message").contains("未找到用户档案"));
    }

    @Test
    void query_cacheHit_noDbAccess() {
        ctx.setUserContactInfo("{\"cached\":true}");

        String result = tools.queryUserContactInfo(ctx);

        assertEquals("{\"cached\":true}", result);
        verifyNoInteractions(userProfileRepository);
    }

    @Test
    void queryBaseLocation_present() {
        stubProfile(existingProfile());

        JSONObject o = JSON.parseObject(tools.queryUserBaseLocation(ctx));

        assertEquals("北京", o.getString("baseCity"));
    }

    @Test
    void queryBaseLocation_absent_promptsAsk() {
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        JSONObject o = JSON.parseObject(tools.queryUserBaseLocation(ctx));

        assertEquals("", o.getString("baseCity"));
        assertTrue(o.getString("message").contains("询问出发城市"));
    }
}
