package com.gogo.travel.agent.common;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * prompt 模板加载器：展开片段引用并注入动态变量。
 *
 */
public final class PromptLoader {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** prompt 资源根目录（classpath）。 */
    private static final String PROMPT_ROOT = "prompts/";

    /** 片段引用指令，如 <code>&#123;&#123;include:fragments/time-rules.md&#125;&#125;</code>。 */
    private static final Pattern INCLUDE_PATTERN = Pattern.compile("\\{\\{include:\\s*([\\w\\-./]+)\\s*}}");

    /** 片段嵌套展开的最大层数，超过视为配置错误。 */
    private static final int MAX_INCLUDE_DEPTH = 5;

    /** 主 prompt 缺失时的兼容兜底文案。 */
    private static final String FALLBACK_PROMPT = "You are a helpful travel assistant.";

    private PromptLoader() {
    }

    /**
     * 加载 prompt 模板并注入所有动态变量（含 current_time）。
     * <p>兼容旧用法，不需要缓存优化的场景仍可使用此方法。</p>
     */
    public static String load(String filename) {
        return render(filename, LocalTime.now().format(TIME_FORMATTER));
    }

    /**
     * 静态加载 prompt 模板：仅注入 current_date 和 current_weekday（每日变化一次），
     * 不注入 current_time，以保持 system prompt 前缀在同一天内完全稳定，
     * 利于百炼隐式缓存命中（相同前缀按 20% 计费）。
     * <p>动态时间由 {@code DynamicTimeInjectionHook} 作为独立 system message 注入。</p>
     */
    public static String loadStatic(String filename) {
        return render(filename, "（见下方动态注入）");
    }

    /**
     * 构建动态时间上下文文本，供 {@code DynamicTimeInjectionHook} 作为独立 system message 注入。
     * <p>放在 system prompt 之后，不影响前缀缓存匹配。</p>
     */
    public static String buildTimeContext() {
        return "当前时间：" + LocalTime.now().format(TIME_FORMATTER);
    }

    /**
     * 加载模板 → 展开片段引用 → 注入变量。
     *
     * @param currentTime {@code current_time} 占位符的取值（实时时间或“见动态注入”占位说明）
     */
    private static String render(String filename, String currentTime) {
        String content = loadResource(PROMPT_ROOT + filename);
        if (content == null || content.isEmpty()) {
            return FALLBACK_PROMPT;
        }
        // 先展开片段，再替换变量，使片段内的占位符同样生效
        content = expandIncludes(content, new LinkedHashSet<>(Set.of(filename)), 0);
        LocalDate today = LocalDate.now();
        // 直接注入权威的"星期几"，避免 LLM 从日期自行推算星期导致"本周"范围算错
        String weekday = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
        return content
                .replace("{{current_date}}", today.format(DATE_FORMATTER))
                .replace("{{current_weekday}}", weekday)
                .replace("{{current_time}}", currentTime);
    }

    /**
     * 递归展开片段引用指令。
     *
     * @param chain 当前引用链（从主 prompt 开始），用于环形引用检测与报错定位
     * @param depth 当前嵌套层数
     * @throws IllegalStateException 片段不存在/为空、环形引用、或嵌套超过上限时抛出
     */
    private static String expandIncludes(String content, Set<String> chain, int depth) {
        if (depth > MAX_INCLUDE_DEPTH) {
            throw new IllegalStateException(
                    "prompt 片段嵌套超过 " + MAX_INCLUDE_DEPTH + " 层，引用链：" + String.join(" -> ", chain));
        }
        Matcher matcher = INCLUDE_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            String path = matcher.group(1);
            if (chain.contains(path)) {
                throw new IllegalStateException(
                        "prompt 片段存在环形引用：" + String.join(" -> ", chain) + " -> " + path);
            }
            String fragment = loadResource(PROMPT_ROOT + path);
            if (fragment == null || fragment.isBlank()) {
                throw new IllegalStateException("prompt 片段不存在或为空：" + PROMPT_ROOT + path
                                                + "（引用链：" + String.join(" -> ", chain) + "）");
            }
            Set<String> nested = new LinkedHashSet<>(chain);
            nested.add(path);
            String expanded = expandIncludes(fragment.strip(), nested, depth + 1);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(expanded));
        }
        if (!matched) {
            return content;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 仅供单测：对任意文本展开片段引用，不涉及变量注入。
     */
    static String expandIncludes(String content) {
        return expandIncludes(content, new LinkedHashSet<>(), 0);
    }

    private static String loadResource(String path) {
        try (var is = PromptLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
