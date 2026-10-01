package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.business.user.entity.UserProfile;
import com.gogo.travel.business.user.repo.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserInfoWriteTools 单元测试：
 * update_user_contact_info 的字段格式校验与"档案不存在自动创建"、
 * update_user_base_location 的更新与缓存失效。
 *
 */
@ExtendWith(MockitoExtension.class)
class UserInfoWriteToolsTest {

    private static final String USER_ID = "u_test";

    @Mock
    private UserProfileRepository userProfileRepository;

    @InjectMocks
    private UserInfoWriteTools tools;

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

    // ============ update_user_contact_info：入参校验 ============

    @Test
    void update_noField_rejected() {
        JSONObject o = JSON.parseObject(tools.updateUserContactInfo(ctx, null, null, null, null, null, null, null));
        assertFalse(o.getBooleanValue("success"));
        verify(userProfileRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "00000000000", "12812345678", "1381234567a", "138123456789"})
    void update_invalidPhone_rejected(String phone) {
        stubProfile(existingProfile());
        JSONObject o = JSON.parseObject(tools.updateUserContactInfo(ctx, null, null, null, null, null, phone, null));
        assertFalse(o.getBooleanValue("success"));
        assertTrue(o.getString("message").contains("手机号"));
        verify(userProfileRepository, never()).save(any());
    }

    @Test
    void update_validPhone_savedAndCacheInvalidated() {
        UserProfile profile = existingProfile();
        stubProfile(profile);
        ctx.setUserContactInfo("{\"cached\":true}");

        JSONObject o = JSON.parseObject(tools.updateUserContactInfo(ctx, null, null, null, null, null, "13812345678", null));

        assertTrue(o.getBooleanValue("success"));
        assertEquals("13812345678", profile.getPhone());
        verify(userProfileRepository).save(profile);
        // 更新成功后必须失效会话缓存
        assertNull(ctx.getUserContactInfo());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1234", "12345678901234567", "1101011990030776341"})
    void update_idCard_invalidNumber_rejected(String idNumber) {
        stubProfile(existingProfile());
        JSONObject o = JSON.parseObject(tools.updateUserContactInfo(ctx, null, null, null, 0, idNumber, null, null));
        assertFalse(o.getBooleanValue("success"));
        assertTrue(o.getString("message").contains("身份证"));
        verify(userProfileRepository, never()).save(any());
    }

    @Test
    void update_idCard_lowercaseX_storedUppercase() {
        UserProfile profile = existingProfile();
        stubProfile(profile);

        JSONObject o = JSON.parseObject(tools.updateUserContactInfo(ctx, null, null, null, 0, "11010119900307761x", null, null));

        assertTrue(o.getBooleanValue("success"));
        assertEquals("11010119900307761X", profile.getIdNumber());
    }

    @Test
    void update_idNumber_validatedAgainstExistingIdType() {
        // 本次未传 id_type，但档案里已是身份证类型，"abc" 应按身份证规则被拒
        UserProfile profile = existingProfile();
        profile.setIdType(0);
        stubProfile(profile);

        JSONObject o = JSON.parseObject(tools.updateUserContactInfo(ctx, null, null, null, null, "abc", null, null));

        assertFalse(o.getBooleanValue("success"));
        assertTrue(o.getString("message").contains("身份证"));
    }

    @Test
    void update_passport_validNumber_saved() {
        UserProfile profile = existingProfile();
        stubProfile(profile);

        JSONObject o = JSON.parseObject(tools.updateUserContactInfo(ctx, null, null, null, 1, "E12345678", null, null));

        assertTrue(o.getBooleanValue("success"));
        assertEquals("E12345678", profile.getIdNumber());
        assertEquals(1, profile.getIdType());
    }

    @Test
    void update_passport_tooShort_rejected() {
        stubProfile(existingProfile());
        JSONObject o = JSON.parseObject(tools.updateUserContactInfo(ctx, null, null, null, 1, "ab", null, null));
        assertFalse(o.getBooleanValue("success"));
        assertTrue(o.getString("message").contains("护照"));
    }

    @Test
    void update_idTypeOutOfRange_rejected() {
        stubProfile(existingProfile());
        JSONObject o = JSON.parseObject(tools.updateUserContactInfo(ctx, null, null, null, 99, null, null, null));
        assertFalse(o.getBooleanValue("success"));
        assertTrue(o.getString("message").contains("证件类型"));
        verify(userProfileRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({"男,M", "M,M", "male,M", "女,F", "f,F", "FEMALE,F"})
    void update_gender_normalized(String input, String expected) {
        UserProfile profile = existingProfile();
        stubProfile(profile);

        JSONObject o = JSON.parseObject(tools.updateUserContactInfo(ctx, null, null, null, null, null, null, input));

        assertTrue(o.getBooleanValue("success"));
        assertEquals(expected, profile.getGender());
    }

    @Test
    void update_gender_unrecognized_rejected() {
        stubProfile(existingProfile());
        JSONObject o = JSON.parseObject(tools.updateUserContactInfo(ctx, null, null, null, null, null, null, "unknown"));
        assertFalse(o.getBooleanValue("success"));
        assertTrue(o.getString("message").contains("gender"));
        verify(userProfileRepository, never()).save(any());
    }

    // ============ update_user_contact_info：档案不存在自动创建 ============

    @Test
    void update_profileAbsent_createdAutomatically() {
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        JSONObject o = JSON.parseObject(tools.updateUserContactInfo(ctx, null, null, "张三", null, null, "13812345678", null));

        assertTrue(o.getBooleanValue("success"));
        assertTrue(o.getBooleanValue("created"));
        ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileRepository).save(captor.capture());
        assertEquals(USER_ID, captor.getValue().getUserId());
        assertEquals("张三", captor.getValue().getChineseName());
        assertEquals("13812345678", captor.getValue().getPhone());
    }

    @Test
    void update_profileAbsent_invalidField_notCreated() {
        // 档案不存在 + 字段非法：校验先拦截，不应创建档案
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        JSONObject o = JSON.parseObject(tools.updateUserContactInfo(ctx, null, null, null, null, null, "12345", null));

        assertFalse(o.getBooleanValue("success"));
        verify(userProfileRepository, never()).save(any());
    }

    // ============ update_user_base_location ============

    @Test
    void updateBaseLocation_blank_rejected() {
        JSONObject o = JSON.parseObject(tools.updateUserBaseLocation(ctx, "  "));
        assertFalse(o.getBooleanValue("success"));
        verify(userProfileRepository, never()).save(any());
    }

    @Test
    void updateBaseLocation_valid_savedAndCacheInvalidated() {
        UserProfile profile = existingProfile();
        stubProfile(profile);
        ctx.setUserBaseLocation("{\"baseCity\":\"北京\"}");

        JSONObject o = JSON.parseObject(tools.updateUserBaseLocation(ctx, "深圳"));

        assertTrue(o.getBooleanValue("success"));
        assertEquals("深圳", profile.getBaseCity());
        verify(userProfileRepository).save(profile);
        // 更新成功后必须失效会话缓存
        assertNull(ctx.getUserBaseLocation());
    }

    @Test
    void updateBaseLocation_profileAbsent_created() {
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        JSONObject o = JSON.parseObject(tools.updateUserBaseLocation(ctx, "深圳"));

        assertTrue(o.getBooleanValue("success"));
        assertTrue(o.getBooleanValue("created"));
        ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileRepository).save(captor.capture());
        assertEquals(USER_ID, captor.getValue().getUserId());
        assertEquals("深圳", captor.getValue().getBaseCity());
    }
}
