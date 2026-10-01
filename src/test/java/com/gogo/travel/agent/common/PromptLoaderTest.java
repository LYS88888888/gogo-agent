package com.gogo.travel.agent.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PromptLoader} 片段引用与变量注入测试，并附带 prompt 目录的离线护栏。
 *
 * <p>护栏部分（与 {@code RagKnowledgeCorpusTest} 的思路一致）：直接扫描 {@code prompts/} 下所有 md，
 * 保证 ① 每个 {@code include} 指向的片段真实存在；② 加载后不残留任何未展开的占位符；
 * ③ 共享片段没有被人重新内联回各 prompt（防止刚抽出来又漂移回去）。</p>
 *
 */
@DisplayName("PromptLoader 片段引用与 prompt 目录护栏")
class PromptLoaderTest {

    /** 引用了「时间处理规则」共享片段的 prompt。 */
    private static final List<String> TIME_RULE_PROMPTS = List.of(
            "info-agent-system.md",
            "itinerary-manage-agent-system.md",
            "itinerary-plan-agent-system.md",
            "reimbursement-agent-system.md");

    private static final String TIME_RULE_INCLUDE = "{{include:fragments/time-rules.md}}";

    /** 片段正文中的标志性文本，用于确认片段确实被展开进来。 */
    private static final String TIME_RULE_MARKER = "### 时间处理规则";
    private static final String TIME_RULE_BODY_MARKER = "\"大后天\"= 当前日期 + 4 天。";

    private static final Pattern INCLUDE_PATTERN = Pattern.compile("\\{\\{include:\\s*([\\w\\-./]+)\\s*}}");

    // ─────────────────────────────────────────────────────────────────────────
    // 片段展开
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("load 会把片段引用展开为片段正文")
    void loadExpandsFragmentInclude() {
        String prompt = PromptLoader.load("info-agent-system.md");

        assertTrue(prompt.contains(TIME_RULE_MARKER), "片段标题未展开");
        assertTrue(prompt.contains(TIME_RULE_BODY_MARKER), "片段正文未展开");
        assertFalse(prompt.contains("{{include:"), "仍残留未展开的引用指令");
    }

    @Test
    @DisplayName("片段内部的变量占位符同样会被注入")
    void variablesInsideFragmentAreInjected() {
        String prompt = PromptLoader.load("info-agent-system.md");

        LocalDate today = LocalDate.now();
        String expectedDate = today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String expectedWeekday = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);

        // current_date / current_weekday 都只出现在片段里，能替换说明"先展开片段再注入变量"的顺序正确
        assertTrue(prompt.contains(expectedDate), "片段内的 current_date 未注入");
        assertTrue(prompt.contains(expectedWeekday), "片段内的 current_weekday 未注入");
        assertFalse(prompt.contains("{{current_date}}"), "仍残留 current_date 占位符");
        assertFalse(prompt.contains("{{current_weekday}}"), "仍残留 current_weekday 占位符");
    }

    @Test
    @DisplayName("loadStatic 同样展开片段，且 current_time 使用动态注入占位说明")
    void loadStaticExpandsFragmentAndKeepsTimePlaceholderStable() {
        String prompt = PromptLoader.loadStatic("info-agent-system.md");

        assertTrue(prompt.contains(TIME_RULE_BODY_MARKER), "片段正文未展开");
        assertFalse(prompt.contains("{{current_time}}"), "仍残留 current_time 占位符");
    }

    @Test
    @DisplayName("所有引用同一片段的 prompt 展开出完全相同的规则正文")
    void allPromptsShareIdenticalTimeRuleBody() {
        String fragmentBody = loadClasspathText("/prompts/fragments/time-rules.md").strip();
        // 片段自身含变量占位符，按同一套规则注入后再比对，避免日期差异造成误判
        String expectedBody = injectVariables(fragmentBody);

        for (String filename : TIME_RULE_PROMPTS) {
            String prompt = PromptLoader.load(filename);
            assertTrue(prompt.contains(expectedBody),
                    filename + " 展开后的时间规则正文与共享片段不一致，说明片段未被正确引用");
        }
    }

    @Test
    @DisplayName("支持片段嵌套引用")
    void supportsNestedInclude() {
        String expanded = PromptLoader.expandIncludes("{{include:fragments/test-nest-outer.md}}");

        assertTrue(expanded.contains("OUTER-MARK"), "外层片段未展开");
        assertTrue(expanded.contains("INNER-MARK"), "内层片段未展开");
        assertFalse(expanded.contains("{{include:"), "仍残留未展开的引用指令");
    }

    @Test
    @DisplayName("指令内允许存在空格")
    void toleratesWhitespaceInsideDirective() {
        String expanded = PromptLoader.expandIncludes("{{include: fragments/test-nest-inner.md }}");
        assertTrue(expanded.contains("INNER-MARK"), "带空格的引用指令未被识别");
    }

    @Test
    @DisplayName("无引用指令的文本原样返回")
    void contentWithoutIncludeIsUnchanged() {
        String content = "# 普通 prompt\n\n没有任何片段引用。";
        assertEquals(content, PromptLoader.expandIncludes(content));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 异常路径：宁可快速失败，也不静默丢掉一整节约束
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("引用不存在的片段时抛异常并给出路径")
    void missingFragmentFailsFast() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PromptLoader.expandIncludes("{{include:fragments/__not_exists__.md}}"));

        assertTrue(error.getMessage().contains("fragments/__not_exists__.md"),
                "异常信息应包含缺失的片段路径，实际：" + error.getMessage());
    }

    @Test
    @DisplayName("片段环形引用被检测并抛异常")
    void cyclicIncludeIsDetected() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PromptLoader.expandIncludes("{{include:fragments/test-cycle-a.md}}"));

        assertTrue(error.getMessage().contains("环形引用"),
                "异常信息应说明环形引用，实际：" + error.getMessage());
    }

    @Test
    @DisplayName("prompt 文件缺失时回退到兜底文案")
    void missingPromptFallsBack() {
        assertEquals("You are a helpful travel assistant.", PromptLoader.load("__not_exists__.md"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // prompt 目录护栏
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("prompts 目录下所有 md 的片段引用都指向真实存在的片段")
    void everyIncludeTargetExists() throws IOException {
        Path promptRoot = resolvePromptRoot();
        List<String> problems = new ArrayList<>();
        int includeCount = 0;

        for (Path promptFile : listPromptFiles(promptRoot)) {
            String content = Files.readString(promptFile, StandardCharsets.UTF_8);
            Matcher matcher = INCLUDE_PATTERN.matcher(content);
            while (matcher.find()) {
                includeCount++;
                String target = matcher.group(1);
                if (!Files.exists(promptRoot.resolve(target))) {
                    problems.add(promptRoot.relativize(promptFile) + " 引用了不存在的片段：" + target);
                }
            }
        }

        assertTrue(problems.isEmpty(), "存在失效的片段引用：\n" + String.join("\n", problems));
        // 防止扫不到文件时本用例假通过：已知至少有 TIME_RULE_PROMPTS 那几处引用
        assertTrue(includeCount >= TIME_RULE_PROMPTS.size(),
                "仅扫到 " + includeCount + " 处片段引用，低于预期，请确认 prompts 目录定位是否正确");
    }

    @Test
    @DisplayName("所有 prompt 加载后不残留未展开的占位符")
    void noUnresolvedPlaceholderAfterLoad() throws IOException {
        Path promptRoot = resolvePromptRoot();
        List<String> problems = new ArrayList<>();

        // 片段自身不单独加载（其占位符由引用方注入），只校验顶层 prompt
        for (Path promptFile : listPromptFiles(promptRoot)) {
            if (promptFile.getParent().endsWith("fragments")) {
                continue;
            }
            String rendered = PromptLoader.loadStatic(promptFile.getFileName().toString());
            if (rendered.contains("{{include:")) {
                problems.add(promptFile.getFileName() + " 残留未展开的片段引用");
            }
            if (rendered.contains("{{current_date}}") || rendered.contains("{{current_weekday}}")
                || rendered.contains("{{current_time}}")) {
                problems.add(promptFile.getFileName() + " 残留未注入的时间变量");
            }
        }

        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("共享片段没有被重新内联回各 prompt")
    void timeRulesAreNotInlinedAgain() throws IOException {
        Path promptRoot = resolvePromptRoot();
        List<String> problems = new ArrayList<>();

        for (Path promptFile : listPromptFiles(promptRoot)) {
            if (promptFile.getParent().endsWith("fragments")) {
                continue;
            }
            String content = Files.readString(promptFile, StandardCharsets.UTF_8);
            if (content.contains(TIME_RULE_BODY_MARKER)) {
                problems.add(promptFile.getFileName() + " 内联了时间处理规则正文，请改为引用 " + TIME_RULE_INCLUDE);
            }
        }

        assertTrue(problems.isEmpty(), String.join("\n", problems));

        // 已知使用该规则的 prompt 必须保留引用，避免被误删导致规则静默丢失
        for (String filename : TIME_RULE_PROMPTS) {
            String content = Files.readString(promptRoot.resolve(filename), StandardCharsets.UTF_8);
            assertTrue(content.contains(TIME_RULE_INCLUDE), filename + " 丢失了时间处理规则的片段引用");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 辅助
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 定位主资源编译产物中的 prompts 目录（{@code target/classes/prompts}），
     * 不含测试夹具，避免护栏用例被 {@code src/test/resources} 下的片段干扰。
     */
    private Path resolvePromptRoot() {
        URL url = getClass().getResource("/prompts/info-agent-system.md");
        assertNotNull(url, "未找到 prompts/info-agent-system.md");
        try {
            return Paths.get(url.toURI()).getParent();
        } catch (Exception e) {
            throw new IllegalStateException("解析 prompts 目录失败", e);
        }
    }

    private List<Path> listPromptFiles(Path promptRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(promptRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .toList();
        }
    }

    private String loadClasspathText(String classpathLocation) {
        URL url = getClass().getResource(classpathLocation);
        assertNotNull(url, "未找到 " + classpathLocation);
        try {
            return Files.readString(Paths.get(url.toURI()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取 " + classpathLocation + " 失败", e);
        }
    }

    /**
     * 按加载器的口径注入时间变量（片段仅使用 current_date / current_weekday），供片段正文比对使用。
     */
    private String injectVariables(String content) {
        LocalDate today = LocalDate.now();
        return content
                .replace("{{current_date}}", today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                .replace("{{current_weekday}}", today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA));
    }
}
