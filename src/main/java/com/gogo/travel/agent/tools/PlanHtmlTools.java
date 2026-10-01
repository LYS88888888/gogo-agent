package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.registry.SseEmitterRegistry;
import com.gogo.travel.business.order.repo.TravelOrderRepository;
import com.gogo.travel.config.sensitive.SensitiveMasker;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行程方案 HTML 输出工具。
 *
 * <p>核心设计动机：在 ReActAgent 架构中，当 LLM 输出同时包含文本和 tool_use 块时，
 * 文本被视为"中间推理"而非最终回复。如果 Agent 将完整方案作为自由文本输出，
 * 用户可能只能看到后续生成的简短摘要。
 *
 * <p>本工具将方案输出从"LLM 自由文本"转变为"显式工具调用"：
 * <ol>
 *   <li>LLM 按 {@code html-plan} skill 的模板组合完整方案 HTML（含内联 CSS）</li>
 *   <li>LLM 调用 {@code save_plan_html} 工具，将 HTML 作为参数传入</li>
 *   <li>工具将 HTML 上传至 MinIO 持久化，并更新差旅单的 {@code plan_html_url} 字段</li>
 *   <li>同时通过 SSE {@code plan_html} 事件将 HTML 推送至前端（实时展示）</li>
 *   <li>工具返回简短成功消息，LLM 再输出一句确认文案作为最终回复</li>
 * </ol>
 *
 * <p>方案 HTML 作为 tool_use 参数传递，不会被 ReActAgent 当作中间推理消费，
 * 从根本上解决了方案文本丢失问题。同时 HTML 持久化到 MinIO，支持历史会话回看。
 *
 */
@Component
public class PlanHtmlTools {

    private static final Logger logger = LoggerFactory.getLogger(PlanHtmlTools.class);

    @Autowired
    private SseEmitterRegistry emitterRegistry;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private TravelOrderRepository travelOrderRepository;

    @Value("${app.minio.bucket}")
    private String bucket;

    /**
     * 保存行程方案 HTML 并推送至前端渲染。
     *
     * <p>LLM 在组合好完整方案 HTML 后调用此工具。工具会：
     * <ul>
     *   <li>将 HTML 上传至 MinIO 持久化（对象 key: plans/{orderId}/{timestamp}.html）</li>
     *   <li>更新差旅单的 plan_html_url 字段（关联行程单 ID 时）</li>
     *   <li>通过 SSE {@code plan_html} 事件将 HTML 推送至前端（实时展示）</li>
     *   <li>返回成功消息，提示 LLM 只需输出简短确认文案</li>
     * </ul>
     *
     * @param html      完整的方案 HTML 字符串，必须包含内联 CSS（单文件 HTML）
     * @param title     方案标题（如"上海 → 杭州 差旅行程方案"），可选
     * @param sessionCtx 会话上下文（框架自动注入，对 LLM 透明）
     * @return JSON 结果字符串
     */
    @Tool(name = "save_plan_html",
            description = "保存完整的行程方案 HTML 并推送给前端渲染。"
                    + "你需先按 html-plan skill 的模板组合好完整的单文件 HTML（含内联 CSS），"
                    + "然后调用此工具传入 HTML 字符串。"
                    + "调用成功后，你只需输出一句简短确认文案（如'方案已生成，请查看下方展示'），"
                    + "不要重复输出方案正文。")
    public String savePlanHtml(
            @ToolParam(name = "html",
                    description = "完整的方案 HTML 字符串，包含内联 CSS，单文件 HTML。"
                            + "按 html-plan skill 的模板和样式约定生成。",
                    required = true) String html,
            @ToolParam(name = "title",
                    description = "方案标题，如'上海 → 杭州 差旅行程方案'",
                    required = false) String title,
            AgentSessionContext sessionCtx) {

        String sessionId = sessionCtx != null ? sessionCtx.getSessionId() : null;
        if (sessionId == null) {
            logger.warn("[PLAN_HTML] 无法获取 sessionId，方案 HTML 未推送");
            return errorResult("无法获取会话 ID，方案推送失败");
        }

        if (html == null || html.isBlank()) {
            logger.warn("[PLAN_HTML] HTML 内容为空, sessionId={}", sessionId);
            return errorResult("HTML 内容不能为空");
        }

        logger.info("[PLAN_HTML] 收到方案 HTML，长度={}, sessionId={}", html.length(), sessionId);

        // 1. 上传 HTML 到 MinIO 持久化
        String travelOrderId = sessionCtx != null ? sessionCtx.getTravelOrderId() : null;
        String objectKey = buildObjectKey(travelOrderId, sessionId);
        String minioUrl = uploadToMinio(objectKey, html);
        boolean minioOk = minioUrl != null;

        // 2. 关联差旅单：更新 plan_html_url 字段
        if (travelOrderId != null && !travelOrderId.isBlank() && minioOk) {
            try {
                boolean updated = travelOrderRepository.updatePlanHtmlUrl(travelOrderId, objectKey);
                if (updated) {
                    logger.info("[PLAN_HTML] 差旅单 {} 的 plan_html_url 已更新为 {}", travelOrderId, objectKey);
                } else {
                    logger.warn("[PLAN_HTML] 差旅单 {} 不存在或更新失败", travelOrderId);
                }
            } catch (Exception e) {
                logger.error("[PLAN_HTML] 更新差旅单 plan_html_url 失败, orderId={}", travelOrderId, e);
            }
        } else if (travelOrderId == null) {
            logger.info("[PLAN_HTML] 无关联差旅单 ID，跳过 plan_html_url 更新（HTML 仍已保存至 MinIO）");
        }

        // 3. 通过 SSE 推送 HTML 至前端（实时展示）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "plan_html");
        data.put("html", html);
        data.put("title", title != null ? title : "差旅行程方案");

        SseEmitter emitter = emitterRegistry.get(sessionId);
        if (emitter != null) {
            try {
                String jsonData = JSON.toJSONString(data);
                String masked = SensitiveMasker.mask(jsonData);
                emitter.send(SseEmitter.event().name("plan_html").data(masked));
                logger.info("[PLAN_HTML] 已推送 plan_html 事件, sessionId={}, html长度={}",
                        sessionId, html.length());
            } catch (IllegalStateException e) {
                logger.debug("[PLAN_HTML] SSE 发送失败，emitter 已完成: {}", e.getMessage());
            } catch (IOException e) {
                logger.error("[PLAN_HTML] SSE 发送失败", e);
            }
        } else {
            logger.warn("[PLAN_HTML] 未找到 sessionId={} 的 SSE emitter，方案未实时推送（HTML 已保存至 MinIO）", sessionId);
        }

        // 4. 返回成功消息给 LLM
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("message", "方案 HTML 已保存并推送至前端，用户将看到完整方案展示。"
                + "你只需输出一句简短确认文案（如'方案已生成，请查看下方完整方案展示'），"
                + "不要重复输出方案正文。");
        result.put("html_length", html.length());
        result.put("minio_saved", minioOk);
        if (minioOk) {
            result.put("object_key", objectKey);
        }
        return result.toJSONString();
    }

    /**
     * 构建 MinIO 对象 key：plans/{travelOrderId or sessionId}/{timestamp}.html
     */
    private String buildObjectKey(String travelOrderId, String sessionId) {
        String idPart = (travelOrderId != null && !travelOrderId.isBlank())
                ? travelOrderId
                : "session_" + sessionId;
        return "plans/" + idPart + "/" + System.currentTimeMillis() + ".html";
    }

    /**
     * 上传 HTML 到 MinIO，返回对象 key（成功）或 null（失败）。
     */
    private String uploadToMinio(String objectKey, String html) {
        try {
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            try (InputStream is = new ByteArrayInputStream(bytes)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucket)
                                .object(objectKey)
                                .stream(is, bytes.length, -1)
                                .contentType("text/html; charset=utf-8")
                                .build());
            }
            logger.info("[PLAN_HTML] HTML 已上传至 MinIO, bucket={}, key={}, 长度={}",
                    bucket, objectKey, bytes.length);
            return objectKey;
        } catch (Exception e) {
            logger.error("[PLAN_HTML] 上传 MinIO 失败, key={}", objectKey, e);
            return null;
        }
    }

    private static String errorResult(String msg) {
        return new JSONObject(Map.of("ok", false, "error", msg)).toJSONString();
    }
}
