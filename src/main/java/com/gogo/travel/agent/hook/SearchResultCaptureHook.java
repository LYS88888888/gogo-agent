package com.gogo.travel.agent.hook;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.context.SessionReactorContext;
import com.gogo.travel.agent.tools.ItineraryPlannerTools;
import com.gogo.travel.agent.tools.store.SearchCandidateStore;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 搜索结果自动捕获 Hook。
 *
 * <p>监听 {@link PostActingEvent}：当 {@code execute_shell_command} 执行的是 tuniu
 * <b>搜索类</b>命令（机票/火车票/酒店搜索）且返回成功时，自动将 stdout 中的原始搜索结果
 * 写入 Redis（按用户隔离），供 {@link ItineraryPlannerTools}
 * 在 {@code candidates="from_redis"} 模式下直接读取。</p>
 *
 * <p>识别的搜索命令：
 * <ul>
 *   <li>{@code tuniu call flight searchLowestPriceFlight} → 机票搜索</li>
 *   <li>{@code tuniu call train searchLowestPriceTrain} → 火车票搜索</li>
 *   <li>{@code tuniu call hotel tuniuHotelSearch} → 酒店搜索</li>
 * </ul>
 * </p>
 *
 */
@Component
public class SearchResultCaptureHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(SearchResultCaptureHook.class);

    private static final String SHELL_TOOL_NAME = "execute_shell_command";

    // 搜索命令识别
    private static final String CMD_FLIGHT_SEARCH = "call flight searchLowestPriceFlight";
    private static final String CMD_TRAIN_SEARCH = "call train searchLowestPriceTrain";
    private static final String CMD_HOTEL_SEARCH = "call hotel tuniuHotelSearch";

    @Autowired
    private SearchCandidateStore searchCandidateStore;

    @Override
    public int priority() {
        return 21;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent postActing) {
            return Mono.deferContextual(ctx -> {
                try {
                    handlePostActing(postActing, ctx);
                } catch (Exception e) {
                    // 捕获失败不得影响主流程
                    logger.error("[SearchCapture] 搜索结果捕获异常", e);
                }
                return Mono.just(event);
            }).map(e -> {
                @SuppressWarnings("unchecked")
                T casted = (T) e;
                return casted;
            });
        }
        return Mono.just(event);
    }

    private void handlePostActing(PostActingEvent event, ContextView ctx) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse == null || !SHELL_TOOL_NAME.equals(toolUse.getName())) {
            return;
        }
        Map<String, Object> input = toolUse.getInput();
        if (input == null) {
            return;
        }
        String command = (String) input.get("command");
        if (command == null) {
            return;
        }

        // 1. 识别搜索类型
        SearchType searchType = resolveSearchType(command);
        if (searchType == null) {
            return;
        }

        // 2. 提取工具执行结果文本并解析为 JSON
        String resultText = extractResultText(event.getToolResult());
        JSONObject resultJson = parseJson(resultText);
        if (resultJson == null) {
            logger.warn("[SearchCapture] 无法解析结果 JSON，跳过, type={}", searchType);
            return;
        }

        // 3. 仅在操作成功时捕获
        if (Boolean.FALSE.equals(resultJson.getBoolean("success"))) {
            logger.info("[SearchCapture] 搜索未成功，跳过, type={}", searchType);
            return;
        }

        // 4. 解包 MCP content，拿到业务 JSON
        JSONObject result = resultJson.getJSONObject("result");
        if (result == null) {
            result = resultJson;
        }
        result = unwrapMcpContent(result);
        if (result == null) {
            logger.warn("[SearchCapture] MCP content 解包失败，跳过, type={}", searchType);
            return;
        }

        // 5. 获取 userId
        AgentSessionContext sessionCtx = SessionReactorContext.from(ctx);
        if (sessionCtx == null) {
            logger.warn("[SearchCapture] Reactor Context 中无会话上下文，跳过");
            return;
        }
        String userId = sessionCtx.getUserId();

        // 6. 解析命令中的 -a 参数（含 departureCityName/arrivalCityName 等）
        JSONObject args = parseArgs(command);

        // 7. 生成 Redis hash field 名
        String field = buildField(searchType, args);

        // 8. 组装并合并存入 Redis
        JSONObject entry = new JSONObject();
        entry.put("type", searchType.name());
        entry.put("args", args != null ? args : new JSONObject());
        entry.put("data", result);

        String existingJson = searchCandidateStore.get(userId, field);
        JSONObject entryToStore;
        if (existingJson != null) {
            JSONObject oldEntry = JSON.parseObject(existingJson);
            entryToStore = mergeEntries(oldEntry, entry, searchType);
            logger.info("[SearchCapture] 合并搜索结果, userId={}, field={}, type={}, mergedSize={}",
                    userId, field, searchType, listSize(entryToStore, searchType));
        } else {
            entryToStore = entry;
            logger.info("[SearchCapture] 首次捕获搜索结果, userId={}, field={}, type={}, size={}",
                    userId, field, searchType, listSize(entry, searchType));
        }
        searchCandidateStore.put(userId, field, entryToStore);
    }

    // ============================== 搜索结果合并逻辑 ==============================

    /**
     * 合并两条搜索结果，按业务唯一标识去重，保留新版本。
     */
    private JSONObject mergeEntries(JSONObject oldEntry, JSONObject newEntry, SearchType type) {
        JSONObject result = new JSONObject(newEntry);  // 以新 entry 为基础（含最新 args/type）

        JSONObject oldData = oldEntry.getJSONObject("data");
        JSONObject newData = newEntry.getJSONObject("data");
        if (oldData == null || newData == null) {
            return result;
        }

        String listField = listFieldName(type);
        JSONArray oldList = oldData.getJSONArray(listField);
        JSONArray newList = newData.getJSONArray(listField);
        JSONArray merged = mergeAndDedup(oldList, newList, type);

        JSONObject mergedData = new JSONObject();
        for (String key : newData.keySet()) {
            if (!listField.equals(key)) {
                mergedData.put(key, newData.get(key));
            }
        }
        mergedData.put(listField, merged);
        result.put("data", mergedData);
        return result;
    }

    /**
     * 合并两个 JSON 数组并按业务唯一标识去重。
     */
    private JSONArray mergeAndDedup(JSONArray oldList, JSONArray newList, SearchType type) {
        if (oldList == null) oldList = new JSONArray();
        if (newList == null) newList = new JSONArray();

        Map<String, JSONObject> dedup = new LinkedHashMap<>();
        for (int i = 0; i < oldList.size(); i++) {
            JSONObject item = oldList.getJSONObject(i);
            dedup.put(dedupKey(item, type), item);
        }
        for (int i = 0; i < newList.size(); i++) {
            JSONObject item = newList.getJSONObject(i);
            dedup.put(dedupKey(item, type), item);
        }

        JSONArray result = new JSONArray();
        result.addAll(dedup.values());
        return result;
    }

    /**
     * 生成去重 key，无法提取业务 key 时用整条 JSON 作为 key。
     */
    private String dedupKey(JSONObject item, SearchType type) {
        if (item == null) {
            return "null_" + System.nanoTime();
        }
        return switch (type) {
            case FLIGHT -> {
                String fn = item.getString("flightNumber");
                String dt = item.getString("departureTime");
                if (fn != null && dt != null) {
                    yield fn + "|" + dt;
                }
                yield item.toJSONString();
            }
            case TRAIN -> {
                String tn = item.getString("trainNum");
                String dt = item.getString("departureTime");
                if (tn != null && dt != null) {
                    yield tn + "|" + dt;
                }
                yield item.toJSONString();
            }
            case HOTEL -> {
                String id = item.getString("hotelId");
                if (id != null && !id.isBlank()) {
                    yield id;
                }
                String name = item.getString("hotelName");
                if (name != null && !name.isBlank()) {
                    yield "name_" + name;
                }
                yield item.toJSONString();
            }
        };
    }

    /** 各搜索类型在 data 中的列表字段名 */
    private static String listFieldName(SearchType type) {
        return switch (type) {
            case FLIGHT, TRAIN -> "data";
            case HOTEL -> "hotels";
        };
    }

    /** 获取 entry 中业务列表的大小（用于日志） */
    private static int listSize(JSONObject entry, SearchType type) {
        if (entry == null) return 0;
        JSONObject data = entry.getJSONObject("data");
        if (data == null) return 0;
        JSONArray list = data.getJSONArray(listFieldName(type));
        return list != null ? list.size() : 0;
    }

    /** 识别搜索类型 */
    private SearchType resolveSearchType(String command) {
        if (command.contains(CMD_FLIGHT_SEARCH)) {
            return SearchType.FLIGHT;
        }
        if (command.contains(CMD_TRAIN_SEARCH)) {
            return SearchType.TRAIN;
        }
        if (command.contains(CMD_HOTEL_SEARCH)) {
            return SearchType.HOTEL;
        }
        return null;
    }

    /** 搜索类型枚举 */
    enum SearchType {
        FLIGHT,
        TRAIN,
        HOTEL
    }

    /**
     * 生成 Redis hash field 名，拼接规则统一由 {@link SearchCandidateStore} 维护。
     */
    private String buildField(SearchType type, JSONObject args) {
        if (args == null) {
            return type.name().toLowerCase() + "_unknown";
        }
        return switch (type) {
            case FLIGHT -> SearchCandidateStore.flightField(
                    safeStr(args, "departureCityName"),
                    safeStr(args, "arrivalCityName"),
                    safeStr(args, "departureDate"));
            case TRAIN -> SearchCandidateStore.trainField(
                    safeStr(args, "departureCityName"),
                    safeStr(args, "arrivalCityName"),
                    safeStr(args, "departureDate"));
            case HOTEL -> SearchCandidateStore.hotelField(
                    safeStr(args, "cityName"),
                    safeStr(args, "checkInDate", "checkIn"),
                    safeStr(args, "checkOutDate", "checkOut"));
        };
    }

    /** 依次尝试多个 key，返回第一个非空字符串值 */
    private String safeStr(JSONObject json, String... keys) {
        if (json == null) {
            return "unknown";
        }
        for (String key : keys) {
            Object v = json.get(key);
            if (v != null && !v.toString().isBlank()) {
                return v.toString().trim();
            }
        }
        return "unknown";
    }

    // ============================== CLI 结果解析工具方法 ==============================

    /**
     * 提取 {@link ToolResultBlock} 中的文本内容。
     */
    private String extractResultText(ToolResultBlock toolResult) {
        if (toolResult == null || toolResult.isSuspended() || toolResult.getOutput() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object block : toolResult.getOutput()) {
            try {
                JSONObject json = JSON.parseObject(JSON.toJSONString(block));
                String text = json.getString("text");
                if (text != null && !text.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(text.trim());
                }
            } catch (Exception ignored) {
                // 跳过无法序列化的块
            }
        }
        String result = sb.toString();
        return result.isBlank() ? null : result;
    }

    /**
     * 从可能夹杂日志的文本中提取并解析第一个 JSON 对象。
     */
    private JSONObject parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        try {
            return JSON.parseObject(trimmed);
        } catch (Exception e) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return JSON.parseObject(trimmed.substring(start, end + 1));
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        return null;
    }

    /**
     * 解包 MCP 工具响应：真实业务 JSON 常以字符串形式嵌套在
     * {@code result.content[].text} 中，逐个尝试解析返回第一个成功对象。
     */
    private JSONObject unwrapMcpContent(JSONObject result) {
        if (result == null) {
            return null;
        }
        JSONArray content = result.getJSONArray("content");
        if (content == null || content.isEmpty()) {
            return result;
        }
        for (int i = 0; i < content.size(); i++) {
            JSONObject item = content.getJSONObject(i);
            if (item == null) {
                continue;
            }
            JSONObject inner = parseJson(item.getString("text"));
            if (inner != null) {
                return inner;
            }
        }
        return result;
    }

    /** 从命令中截取 {@code -a '{...}'} 的入参 JSON。 */
    private JSONObject parseArgs(String command) {
        try {
            int first = command.indexOf('{');
            int last = command.lastIndexOf('}');
            if (first >= 0 && last > first) {
                return JSON.parseObject(command.substring(first, last + 1));
            }
        } catch (Exception e) {
            logger.debug("[SearchCapture] 解析命令入参失败: {}", e.getMessage());
        }
        return null;
    }
}
