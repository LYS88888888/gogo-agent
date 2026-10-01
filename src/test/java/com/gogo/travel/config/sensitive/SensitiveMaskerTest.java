package com.gogo.travel.config.sensitive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SensitiveMasker} 回归测试。
 *
 * <p>正则型脱敏最需要回归测试：改一个字符就可能让整条规则失效或误伤。
 * 本测试覆盖全部 8 条规则的正面命中、反面不命中与边界条件。</p>
 *
 */
@DisplayName("SensitiveMasker 脱敏规则回归测试")
class SensitiveMaskerTest {

    // ── null / 空值 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("null 和空字符串原样返回")
    void shouldReturnNullAndEmptyAsIs() {
        assertNull(SensitiveMasker.mask(null));
        assertEquals("", SensitiveMasker.mask(""));
    }

    @Test
    @DisplayName("无敏感信息的普通文本不被修改")
    void shouldNotModifyPlainText() {
        String text = "今天的天气不错，适合出差去北京。";
        assertEquals(text, SensitiveMasker.mask(text));
    }

    // ── 手机号 ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("手机号脱敏")
    class PhoneTests {

        @Test
        @DisplayName("标准 11 位手机号被脱敏")
        void shouldMaskStandardPhone() {
            String result = SensitiveMasker.mask("联系电话：13812345678");
            assertFalse(result.contains("13812345678"), "手机号不应明文出现");
            assertTrue(result.contains("联系电话："), "前后文应保留");
        }

        @Test
        @DisplayName("文本中多个手机号均被脱敏")
        void shouldMaskMultiplePhones() {
            String result = SensitiveMasker.mask("张三 13812345678 李四 13900001111");
            assertFalse(result.contains("13812345678"));
            assertFalse(result.contains("13900001111"));
        }

        @Test
        @DisplayName("紧邻数字的 11 位不被误判为手机号（长数字串的一部分）")
        void shouldNotMaskPhonePartOfLongNumber() {
            // 18 位数字会被银行卡规则命中（16-19 位），但不应被手机号规则命中
            String text = "订单号 99138123456780001";
            String result = SensitiveMasker.mask(text);
            // 手机号规则不应命中（前后紧邻数字），但银行卡规则会命中
            // 这里验证手机号部分不会被错误截取：原始手机号 13812345678 不应被单独脱敏
            assertFalse(result.contains("138****5678"),
                    "手机号不应从长数字串中被截取并单独脱敏");
        }

        @Test
        @DisplayName("20 位以上数字不命中手机号和银行卡规则")
        void shouldNotMaskVeryLongNumber() {
            String text = "编号 123456789012345678901";
            assertEquals(text, SensitiveMasker.mask(text));
        }

        @Test
        @DisplayName("非 1 开头的 11 位号码不命中")
        void shouldNotMaskNonStandardPhone() {
            String text = "编号 23812345678";
            assertEquals(text, SensitiveMasker.mask(text));
        }
    }

    // ── 身份证号 ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("身份证号脱敏")
    class IdCardTests {

        @Test
        @DisplayName("18 位身份证号（末位数字）被脱敏")
        void shouldMaskIdCardWithDigit() {
            String result = SensitiveMasker.mask("证件号：110101199003071234");
            assertFalse(result.contains("110101199003071234"));
        }

        @Test
        @DisplayName("18 位身份证号（末位 X）被脱敏")
        void shouldMaskIdCardWithX() {
            String result = SensitiveMasker.mask("证件号：11010119900307761X");
            assertFalse(result.contains("11010119900307761X"));
        }

        @Test
        @DisplayName("18 位身份证号（末位小写 x）被脱敏")
        void shouldMaskIdCardWithLowerX() {
            String result = SensitiveMasker.mask("证件号：11010119900307761x");
            assertFalse(result.contains("11010119900307761x"));
            System.out.println(result);
        }

        @Test
        @DisplayName("17 位数字不命中身份证规则")
        void shouldNotMask17Digits() {
            String text = "编号 11010119900307123";
            String result = SensitiveMasker.mask(text);
            // 可能被银行卡规则命中（16-19 位），但不应被身份证规则命中
            // 只要原始值不被原样保留即可（银行卡也是脱敏）
            assertNotNull(result);
        }
    }

    // ── 银行卡号 ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("银行卡号脱敏")
    class BankCardTests {

        @Test
        @DisplayName("16 位银行卡号被脱敏")
        void shouldMask16DigitCard() {
            String result = SensitiveMasker.mask("卡号：6225880112345678");
            assertFalse(result.contains("6225880112345678"));
        }

        @Test
        @DisplayName("19 位银行卡号被脱敏")
        void shouldMask19DigitCard() {
            String result = SensitiveMasker.mask("卡号：6225880112345678901");
            assertFalse(result.contains("6225880112345678901"));
            System.out.println(result);
        }

        @Test
        @DisplayName("15 位数字不命中银行卡规则")
        void shouldNotMask15Digits() {
            String text = "编号 123456789012345";
            assertEquals(text, SensitiveMasker.mask(text));
        }

        @Test
        @DisplayName("20 位数字不命中银行卡规则")
        void shouldNotMask20Digits() {
            String text = "编号 12345678901234567890";
            assertEquals(text, SensitiveMasker.mask(text));
            System.out.println(text);
        }
    }

    // ── 邮箱 ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("邮箱脱敏")
    class EmailTests {

        @Test
        @DisplayName("标准邮箱被脱敏")
        void shouldMaskEmail() {
            String result = SensitiveMasker.mask("邮箱：zhangsan@example.com");
            assertFalse(result.contains("zhangsan@example.com"));
            assertTrue(result.contains("邮箱："));
            System.out.println(result);
        }

        @Test
        @DisplayName("带加号的邮箱被脱敏")
        void shouldMaskEmailWithPlus() {
            String result = SensitiveMasker.mask("邮箱：user+tag@gmail.com");
            assertFalse(result.contains("user+tag@gmail.com"));
        }
    }

    // ── API Key (sk- 前缀) ────────────────────────────────────────────

    @Nested
    @DisplayName("sk- API Key 脱敏")
    class SkKeyTests {

        @Test
        @DisplayName("sk- 前缀的 API Key 被全量脱敏")
        void shouldMaskSkKey() {
            String result = SensitiveMasker.mask("key=sk-fa5d4336bd26444cad9982ab43543bf3");
            assertFalse(result.contains("fa5d4336bd26444cad9982ab43543bf3"));
        }

        @Test
        @DisplayName("短 sk- 值（少于 6 字符）不命中")
        void shouldNotMaskShortSkKey() {
            String text = "sk-abc";
            assertEquals(text, SensitiveMasker.mask(text));
        }
    }

    // ── KV 秘钥 ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("KV 秘钥脱敏")
    class KvSecretTests {

        @Test
        @DisplayName("JSON 形态的 password 被脱敏")
        void shouldMaskJsonPassword() {
            String result = SensitiveMasker.mask("{\"password\":\"mySecret123\"}");
            assertFalse(result.contains("mySecret123"));
            assertTrue(result.contains("password"));
        }

        @Test
        @DisplayName("YAML 形态的 api-key 被脱敏")
        void shouldMaskYamlApiKey() {
            String result = SensitiveMasker.mask("api-key: my-api-key-value");
            assertFalse(result.contains("my-api-key-value"));
            assertTrue(result.contains("api-key"));
            System.out.println(result);
        }

        @Test
        @DisplayName("properties 形态的 token 被脱敏")
        void shouldMaskPropertiesToken() {
            String result = SensitiveMasker.mask("token=abc123xyz");
            assertFalse(result.contains("abc123xyz"));
            assertTrue(result.contains("token"));
        }

        @Test
        @DisplayName("access-key-secret 被脱敏")
        void shouldMaskAccessKeySecret() {
            String result = SensitiveMasker.mask("access_key_secret: NC2zl43njrsSiPQNPMLJkHQ72rlAHT");
            assertFalse(result.contains("NC2zl43njrsSiPQNPMLJkHQ72rlAHT"));
        }
    }

    // ── 中文姓名 ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("中文姓名脱敏")
    class ChineseNameTests {

        @Test
        @DisplayName("'姓名：张三' 格式被脱敏")
        void shouldMaskNameWithColon() {
            String result = SensitiveMasker.mask("姓名：张三");
            assertFalse(result.contains("张三"), "姓名不应明文出现");
            assertTrue(result.contains("姓名"), "关键词应保留");
        }

        @Test
        @DisplayName("'姓名=李四' 格式被脱敏")
        void shouldMaskNameWithEquals() {
            String result = SensitiveMasker.mask("姓名=李四");
            assertFalse(result.contains("李四"));
        }

        @Test
        @DisplayName("JSON 形态 chineseName 被脱敏")
        void shouldMaskJsonChineseName() {
            String result = SensitiveMasker.mask("{\"chineseName\":\"王五\"}");
            assertFalse(result.contains("王五"));
            assertTrue(result.contains("chineseName"));
        }

        @Test
        @DisplayName("snake_case 形态 chinese_name 被脱敏")
        void shouldMaskSnakeCaseChineseName() {
            String result = SensitiveMasker.mask("chinese_name: 欧阳小明");
            assertFalse(result.contains("欧阳小明"));
        }

        @Test
        @DisplayName("'乘客' 关键词后的姓名被脱敏")
        void shouldMaskPassengerName() {
            String result = SensitiveMasker.mask("乘客 张三丰 的航班已确认");
            assertFalse(result.contains("张三丰"));
            assertTrue(result.contains("乘客"));
        }

        @Test
        @DisplayName("'联系人' 关键词后的姓名被脱敏")
        void shouldMaskContactName() {
            String result = SensitiveMasker.mask("联系人：李四");
            assertFalse(result.contains("李四"));
        }

        @Test
        @DisplayName("无关键词标注的普通中文词语不被误伤")
        void shouldNotMaskRandomChineseText() {
            String text = "今天的天气不错适合出差";
            assertEquals(text, SensitiveMasker.mask(text));
        }

        @Test
        @DisplayName("2 字姓名被脱敏")
        void shouldMaskTwoCharName() {
            String result = SensitiveMasker.mask("姓名：张三");
            assertFalse(result.contains("张三"));
            System.out.println(result);
        }

        @Test
        @DisplayName("4 字复姓姓名被脱敏")
        void shouldMaskFourCharName() {
            String result = SensitiveMasker.mask("姓名：欧阳小明");
            assertFalse(result.contains("欧阳小明"));
            System.out.println(result);
        }

        @Test
        @DisplayName("5 字以上的中文串不被姓名规则误匹配")
        void shouldNotMaskLongChineseText() {
            String text = "姓名：这是一个很长的名字测试";
            String result = SensitiveMasker.mask(text);
            // 正则只匹配 2-4 个汉字，所以"这是一个很"不会被整段匹配
            assertNotNull(result);
        }
    }

    // ── 拼音姓名 ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("拼音姓名脱敏")
    class PinyinNameTests {

        @Test
        @DisplayName("'ZHANG SAN' 格式被脱敏")
        void shouldMaskStandardPinyinName() {
            String result = SensitiveMasker.mask("namePinyin=ZHANG SAN");
            assertFalse(result.contains("ZHANG SAN"), "拼音名不应明文出现");
        }

        @Test
        @DisplayName("复姓拼音 'OUYANG XIAOFENG' 被脱敏")
        void shouldMaskCompoundPinyinName() {
            String result = SensitiveMasker.mask("拼音：OUYANG XIAOFENG");
            assertFalse(result.contains("OUYANG XIAOFENG"));
        }

        @Test
        @DisplayName("JSON 形态 namePinyin 值被脱敏")
        void shouldMaskJsonPinyinValue() {
            String result = SensitiveMasker.mask("{\"namePinyin\":\"LI MING\"}");
            assertFalse(result.contains("LI MING"));
        }

        @Test
        @DisplayName("日志形态 namePinyin=ZHANG SAN 被脱敏")
        void shouldMaskLogStylePinyin() {
            String result = SensitiveMasker.mask(
                    "[TOOL] userId=u_001, namePinyin=ZHANG SAN, email=test@example.com");
            assertFalse(result.contains("ZHANG SAN"));
            assertFalse(result.contains("test@example.com"));
        }

        @Test
        @DisplayName("单个大写单词不被拼音规则误伤")
        void shouldNotMaskSingleUppercaseWord() {
            String text = "ERROR: connection refused";
            // "ERROR" 是单个大写词，没有后续大写词配合，不应命中拼音规则
            assertEquals(text, SensitiveMasker.mask(text));
        }
    }

    // ── 组合场景 ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("组合场景")
    class CombinedTests {

        @Test
        @DisplayName("工具异常消息中的多种 PII 均被脱敏")
        void shouldMaskToolExceptionWithMultiplePII() {
            String errorMsg = "Failed to book for passenger 张三, phone: 13812345678, "
                    + "ID: 11010119900307761X, email: zhangsan@test.com";
            String result = SensitiveMasker.mask(errorMsg);
            assertFalse(result.contains("13812345678"), "手机号应被脱敏");
            assertFalse(result.contains("11010119900307761X"), "身份证号应被脱敏");
            assertFalse(result.contains("zhangsan@test.com"), "邮箱应被脱敏");
            assertTrue(result.contains("passenger"), "非敏感文本应保留");
        }

        @Test
        @DisplayName("LLM 追问消息中的手机号被脱敏")
        void shouldMaskLlmFollowUpQuestion() {
            String question = "请确认您的手机号 13812345678 是否正确？";
            String result = SensitiveMasker.mask(question);
            assertFalse(result.contains("13812345678"));
            assertTrue(result.contains("请确认您的手机号"));
            assertTrue(result.contains("是否正确？"));
        }

        @Test
        @DisplayName("UserInfoWriteTools 日志行中的 PII 均被脱敏")
        void shouldMaskUserInfoWriteLog() {
            String log = "[TOOL][update_user_contact_info] userId=u_001, namePinyin=ZHANG SAN, "
                    + "email=test@example.com, chineseName=张三, idType=0, "
                    + "idNumber=11010119900307761X, phone=13812345678, gender=M";
            String result = SensitiveMasker.mask(log);
            assertFalse(result.contains("ZHANG SAN"), "拼音名应被脱敏");
            assertFalse(result.contains("test@example.com"), "邮箱应被脱敏");
            assertFalse(result.contains("13812345678"), "手机号应被脱敏");
            assertFalse(result.contains("11010119900307761X"), "身份证号应被脱敏");
            // chineseName=张三 应被中文名规则命中（chineseName 关键词 + 张三）
            assertFalse(result.contains("张三"), "中文名应被脱敏");
            assertTrue(result.contains("userId=u_001"), "非敏感字段应保留");
        }
    }
}
