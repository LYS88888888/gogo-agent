package com.gogo.travel.utils;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JsonUtil 单元测试：覆盖 LLM 返回 JSON 的各类修复场景。
 *
 * <p>测试重点：
 * <ul>
 *   <li>合法 JSON（含字符串值内部的中文引号/单引号）不触发破坏性替换</li>
 *   <li>markdown 代码块提取、垃圾字符清理、尾部逗号、缺失引号、转义字符修复</li>
 *   <li>非标准引号做 JSON 分隔符时的上下文感知修复</li>
 *   <li>无法修复时的降级包装</li>
 * </ul>
 *
 */
@DisplayName("JsonUtil 单元测试")
class JsonUtilTest {

    // ============================== fixJson — 合法 JSON 直接返回 ==============================

    @Test
    @DisplayName("合法 JSON 直接返回，不做任何修改")
    void fixJson_validJson_returnedAsIs() {
        String input = "{\"key\":\"value\"}";
        String result = JsonUtil.fixJson(input);
        assertEquals(input, result);
    }

    @Test
    @DisplayName("合法 JSON 数组直接返回")
    void fixJson_validJsonArray_returnedAsIs() {
        String input = "[1,2,3]";
        String result = JsonUtil.fixJson(input);
        assertEquals(input, result);
    }

    @Test
    @DisplayName("合法嵌套 JSON 直接返回")
    void fixJson_validNestedJson_returnedAsIs() {
        String input = "{\"outer\":{\"inner\":[\"a\",\"b\"]}}";
        String result = JsonUtil.fixJson(input);
        assertEquals(input, result);
    }

    // ─── 核心 bug：字符串值内部的中文引号不被破坏 ───

    @Test
    @DisplayName("合法 JSON 含字符串值内部的中文单引号：不破坏、直接返回")
    void fixJson_validJsonWithChineseSingleQuotesInsideString_notBroken() {
        // '时间最短' 使用中文单引号(U+2018/U+2019)，位于字符串值内部
        String input = "{\"key\":\"但作为\u2018时间最短\u2019方案，路径合理\"}";
        String result = JsonUtil.fixJson(input);
        assertTrue(JsonUtil.isValidJson(result), "修复后应为合法 JSON");

        JsonNode node = JsonUtil.fixAndParse(input);
        String value = node.get("key").asText();
        assertTrue(value.contains("\u2018时间最短\u2019"), "中文单引号应保留：" + value);
    }

    @Test
    @DisplayName("合法 JSON 含字符串值内部的中文双引号：不破坏、直接返回")
    void fixJson_validJsonWithChineseDoubleQuotesInsideString_notBroken() {
        // "核心时段" 使用中文双引号(U+201C/U+201D)，位于字符串值内部
        String input = "{\"key\":\"正餐核心\u201C时段\u201D，需注意\"}";
        String result = JsonUtil.fixJson(input);
        assertTrue(JsonUtil.isValidJson(result));

        JsonNode node = JsonUtil.fixAndParse(input);
        String value = node.get("key").asText();
        assertTrue(value.contains("\u201C时段\u201D"), "中文双引号应保留：" + value);
    }

    @Test
    @DisplayName("复现实际 LLM 体验评估输出：字符串值内 '时间最短' 不被替换为双引号")
    void fixJson_llmExperienceOutput_chineseSingleQuotesPreserved() {
        // 复现实际 bug：P2 方案 key_findings 中 '时间最短' 使用中文单引号
        String input = "{\"per_proposal\":[{\"proposal_id\":\"P2\","
                + "\"key_findings\":[\"路径合理性：直飞路径合理，"
                + "但作为\u2018时间最短\u2019方案，其早班机影响收益\"]}]}";

        String result = JsonUtil.fixJson(input);
        assertTrue(JsonUtil.isValidJson(result), "修复后应为合法 JSON");

        JsonNode node = JsonUtil.fixAndParse(input);
        String finding = node.path("per_proposal").get(0)
                .path("key_findings").get(0).asText();
        assertTrue(finding.contains("\u2018时间最短\u2019"),
                "中文单引号应保留：" + finding);
    }

    @Test
    @DisplayName("markdown 包裹的 JSON 含字符串值内部中文单引号：正确提取且不破坏")
    void fixJson_markdownWithChineseQuotesInValue_extractedAndPreserved() {
        String input = "```json\n{\"key\":\"作为\u2018时间最短\u2019方案\"}\n```";
        String result = JsonUtil.fixJson(input);
        assertTrue(JsonUtil.isValidJson(result));
        JsonNode node = JsonUtil.fixAndParse(input);
        assertTrue(node.get("key").asText().contains("\u2018时间最短\u2019"));
    }

    // ============================== fixJson — markdown 代码块提取 ==============================

    @Test
    @DisplayName("markdown 代码块(json 标记)被正确剥离")
    void fixJson_markdownFenceWithJson_stripped() {
        String input = "```json\n{\"key\":\"value\"}\n```";
        String result = JsonUtil.fixJson(input);
        assertEquals("{\"key\":\"value\"}", result);
    }

    @Test
    @DisplayName("markdown 代码块(无标记)被正确剥离")
    void fixJson_markdownFenceNoLanguage_stripped() {
        String input = "```\n{\"key\":\"value\"}\n```";
        String result = JsonUtil.fixJson(input);
        assertTrue(JsonUtil.isValidJson(result));
    }

    @Test
    @DisplayName("markdown 代码块前后有额外文本时正确提取")
    void fixJson_markdownFenceWithSurroundingText_extracted() {
        String input = "以下是评估结果：\n```json\n{\"key\":\"value\"}\n```\n以上。";
        String result = JsonUtil.fixJson(input);
        assertTrue(JsonUtil.isValidJson(result));
    }

    // ============================== fixJson — 非标准引号做 JSON 分隔符 ==============================

    @Test
    @DisplayName("中文双引号做 JSON 分隔符：替换为 ASCII 双引号")
    void fixJson_chineseDoubleQuotesAsDelimiters_replaced() {
        // LLM 用中文双引号 " " 做所有 JSON 分隔符
        String input = "{\u201Ckey\u201D:\u201Cvalue\u201D}";
        String result = JsonUtil.fixJson(input);
        assertEquals("{\"key\":\"value\"}", result);
    }

    @Test
    @DisplayName("中文单引号做 JSON 分隔符：替换为 ASCII 双引号")
    void fixJson_chineseSingleQuotesAsDelimiters_replaced() {
        String input = "{\u2018key\u2019:\u2018value\u2019}";
        String result = JsonUtil.fixJson(input);
        assertTrue(JsonUtil.isValidJson(result));
    }

    @Test
    @DisplayName("ASCII 单引号做 JSON 分隔符：替换为双引号")
    void fixJson_asciiSingleQuotesAsDelimiters_replaced() {
        String input = "{'key':'value'}";
        String result = JsonUtil.fixJson(input);
        assertTrue(JsonUtil.isValidJson(result));
    }

    // ============================== fixJson — 其他修复 ==============================

    @Test
    @DisplayName("对象尾部逗号被移除")
    void fixJson_objectTrailingComma_removed() {
        String input = "{\"key\":\"value\",}";
        String result = JsonUtil.fixJson(input);
        assertTrue(JsonUtil.isValidJson(result));
    }

    @Test
    @DisplayName("数组尾部逗号被移除")
    void fixJson_arrayTrailingComma_removed() {
        String input = "{\"arr\":[1,2,3,]}";
        String result = JsonUtil.fixJson(input);
        assertTrue(JsonUtil.isValidJson(result));
    }

    @Test
    @DisplayName("无引号的键名被补全引号")
    void fixJson_unquotedKey_quoted() {
        String input = "{key:\"value\"}";
        String result = JsonUtil.fixJson(input);
        assertTrue(JsonUtil.isValidJson(result));
        JsonNode node = JsonUtil.fixAndParse(input);
        assertEquals("value", node.get("key").asText());
    }

    @Test
    @DisplayName("字符串值内部的换行符被替换为空格")
    void fixJson_newlinesInsideString_replacedWithSpace() {
        String input = "{\"key\":\"line1\nline2\"}";
        String result = JsonUtil.fixJson(input);
        assertTrue(JsonUtil.isValidJson(result));
        JsonNode node = JsonUtil.fixAndParse(input);
        assertEquals("line1 line2", node.get("key").asText());
    }

    @Test
    @DisplayName("JSON 前后的垃圾字符被移除")
    void fixJson_garbageText_removed() {
        String input = "Here is the result:\n{\"key\":\"value\"}\nDone.";
        String result = JsonUtil.fixJson(input);
        assertTrue(JsonUtil.isValidJson(result));
    }

    // ============================== fixJson — 边界情况 ==============================

    @Test
    @DisplayName("null 输入返回空对象")
    void fixJson_nullInput_returnsEmptyObject() {
        assertEquals("{}", JsonUtil.fixJson(null));
    }

    @Test
    @DisplayName("空字符串输入返回空对象")
    void fixJson_emptyInput_returnsEmptyObject() {
        assertEquals("{}", JsonUtil.fixJson(""));
        assertEquals("{}", JsonUtil.fixJson("   "));
    }

    @Test
    @DisplayName("无法修复的纯文本降级包装为 {content: ...}")
    void fixJson_irreducibleText_wrappedAsSimpleJson() {
        String input = "这是一段完全不是 JSON 的纯文本";
        String result = JsonUtil.fixJson(input);
        assertTrue(JsonUtil.isValidJson(result));
        JsonNode node = JsonUtil.fixAndParse(input);
        assertTrue(node.has("content") || node.has("error"),
                "应被包装为 content 或 error 字段");
    }

    // ============================== isValidJson ==============================

    @Test
    @DisplayName("isValidJson 正确判断 JSON 有效性")
    void isValidJson_variousInputs() {
        assertTrue(JsonUtil.isValidJson("{\"key\":\"value\"}"));
        assertTrue(JsonUtil.isValidJson("[1,2,3]"));
        assertTrue(JsonUtil.isValidJson("null"));
        assertTrue(JsonUtil.isValidJson("\"string\""));
        assertTrue(JsonUtil.isValidJson("42"));

        assertFalse(JsonUtil.isValidJson("{key:value}"));
        assertFalse(JsonUtil.isValidJson(null));
    }

    // ============================== fixAndParse ==============================

    @Test
    @DisplayName("fixAndParse 合法 JSON 返回正确 JsonNode")
    void fixAndParse_validJson_returnsNode() {
        JsonNode node = JsonUtil.fixAndParse("{\"key\":\"value\"}");
        assertEquals("value", node.get("key").asText());
    }

    @Test
    @DisplayName("fixAndParse 无效输入降级包装为 content 节点")
    void fixAndParse_invalidInput_wrappedAsContent() {
        JsonNode node = JsonUtil.fixAndParse("totally not json");
        assertTrue(node.isObject());
        assertTrue(node.has("content"));
    }

    // ============================== prettify ==============================

    @Test
    @DisplayName("prettify 格式化 JSON 字符串")
    void prettify_validJson_formatted() {
        String input = "{\"key\":\"value\"}";
        String result = JsonUtil.prettify(input);
        assertTrue(result.contains("\n"), "格式化后应含换行");
        assertTrue(result.contains("\"key\""));
    }

    @Test
    @DisplayName("prettify 无效输入返回原始字符串")
    void prettify_invalidInput_returnsOriginal() {
        String input = "not json";
        String result = JsonUtil.prettify(input);
        assertEquals(input, result);
    }
}
