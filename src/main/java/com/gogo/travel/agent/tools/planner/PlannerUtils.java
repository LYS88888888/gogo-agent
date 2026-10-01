package com.gogo.travel.agent.tools.planner;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.tools.ItineraryPlannerTools;
import com.gogo.travel.utils.JsonUtil;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 行程规划工具通用静态方法集合。
 *
 * <p>包含日期时间解析、JSON 访问、数值格式化等无状态的辅助方法，
 * 供 {@link ItineraryPlannerTools} 及 planner 包内各类共用。
 *
 */
public final class PlannerUtils {

    private PlannerUtils() {
    }

    // ============================== 日期时间解析 ==============================

    public static final DateTimeFormatter[] DT_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
    };

    public static LocalDateTime parseDt(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().replace("Z", "");
        if (v.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter fmt : DT_FORMATS) {
            try {
                return LocalDateTime.parse(v, fmt);
            } catch (Exception ignore) {
                // try next
            }
        }
        try {
            return LocalDate.parse(v, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        } catch (Exception ignore) {
            return null;
        }
    }

    public static Integer durationMinutes(String dep, String arr) {
        LocalDateTime d = parseDt(dep);
        LocalDateTime a = parseDt(arr);
        if (d == null || a == null) {
            return null;
        }
        return (int) Math.round((a.toEpochSecond(ZoneOffset.UTC) - d.toEpochSecond(ZoneOffset.UTC)) / 60.0);
    }

    public static String formatDuration(Integer minutes) {
        if (minutes == null) {
            return null;
        }
        return String.format("%dh%02dm", minutes / 60, minutes % 60);
    }

    // ============================== 数值计算 ==============================

    /**
     * 线性归一化到 [0,100]。higherIsBetter=false → 值越小分越高。lo==hi 时返回 100。
     */
    public static double minmaxScore(Double value, double lo, double hi, boolean higherIsBetter) {
        if (value == null) {
            return 0.0;
        }
        if (Double.compare(hi, lo) == 0) {
            return 100.0;
        }
        double ratio = (value - lo) / (hi - lo);
        if (!higherIsBetter) {
            ratio = 1.0 - ratio;
        }
        return round2(Math.clamp(ratio, 0.0, 1.0) * 100.0);
    }

    public static double round2(double x) {
        return Math.round(x * 100.0) / 100.0;
    }

    public static double round1(double x) {
        return Math.round(x * 10.0) / 10.0;
    }

    public static String fmtNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    public static double parseDoubleSafe(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ============================== JSON 访问辅助 ==============================

    public static String getStr(JSONObject o, String key) {
        Object v = o.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static Double getDbl(JSONObject o, String key) {
        Object v = o.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number num) {
            return num.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    public static double getDblOr(JSONObject o, String key, double def) {
        Double v = getDbl(o, key);
        return v != null ? v : def;
    }

    public static int getIntOr(JSONObject o, String key, int def) {
        Double v = getDbl(o, key);
        return v != null ? (int) Math.round(v) : def;
    }

    public static Integer getInt(JSONObject o, String key) {
        Object v = o.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number num) {
            return num.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    public static int orZero(Integer val) {
        return val != null ? val : 0;
    }

    // ============================== 字符串辅助 ==============================

    public static String trimOrEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    public static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ============================== JSON 容器辅助 ==============================

    public static JSONObject nullSafe(JSONObject o) {
        return o != null ? o : new JSONObject();
    }

    public static JSONArray nullSafeArr(JSONArray a) {
        return a != null ? a : new JSONArray();
    }

    public static JSONObject parseJsonOrEmpty(String json) {
        return (json != null && !json.isBlank()) ? JSON.parseObject(JsonUtil.fixJson(json)) : new JSONObject();
    }

    public static JSONObject copyFields(JSONObject source, List<String> fields) {
        JSONObject target = new JSONObject(fields.size());
        for (String field : fields) {
            target.put(field, source.get(field));
        }
        return target;
    }

    public static String errorResult(String msg) {
        return new JSONObject(Map.of("ok", false, "error", msg)).toJSONString();
    }
}
