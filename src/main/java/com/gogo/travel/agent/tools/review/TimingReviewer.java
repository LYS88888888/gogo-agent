package com.gogo.travel.agent.tools.review;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 维度 2：时间安排合理性校验。
 * 检查日期逻辑、到达/返程缓冲、中转时间、红眼航班。
 *
 */
public class TimingReviewer implements ObjectiveReviewer {

    // ============================== 阈值常量 ==============================

    private static final int MIN_DOMESTIC_TRANSFER_MINUTES = 60;
    private static final int MIN_INTL_TRANSFER_MINUTES = 90;
    private static final int MAX_TRANSFER_MINUTES = 240;
    private static final int MIN_ARRIVAL_BUFFER_MINUTES = 60;
    private static final int MIN_RETURN_BUFFER_MINUTES = 60;
    private static final int RED_EYE_DEPARTURE_HOUR = 22;
    private static final int RED_EYE_ARRIVAL_HOUR = 6;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm");

    // ============================== 内部数据结构 ==============================

    /**
     * 时间检查问题严重级别
     */
    private enum Severity {WARNING, FAIL;}

    private record TimingIssue(Severity severity, String message) {
    }

    // ============================== 校验入口 ==============================

    @Override
    public void review(JSONObject itinerary, JSONObject policy, ReviewCollector collector) {
        JSONArray days = itinerary.getJSONArray("days");
        if (days == null) {
            collector.addCheck(new CheckResult("时间安排合理性", "warning", "无每日行程，无法校验时间"));
            return;
        }

        List<TimingIssue> problems = new ArrayList<>();
        checkDateValidity(itinerary, policy, problems);

        for (int i = 0; i < days.size(); i++) {
            JSONObject day = days.getJSONObject(i);
            String dateStr = day.getString("date");

            if (i == 0 && day.containsKey("outbound_transport")) {
                checkArrivalBuffer(day, dateStr, problems);
            }
            if (day.containsKey("return_transport")) {
                checkReturnBuffer(day, dateStr, problems);
            }
            if (day.containsKey("outbound_transport")) {
                checkTransferTime(day, dateStr, problems);
            }
            // 红眼航班检测
            JSONObject outbound = day.getJSONObject("outbound_transport");
            if (outbound != null) {
                checkRedEyeFlight(outbound, "去程", dateStr, problems);
            }
            JSONObject returnT = day.getJSONObject("return_transport");
            if (returnT != null) {
                checkRedEyeFlight(returnT, "返程", dateStr, problems);
            }
        }

        if (problems.isEmpty()) {
            collector.addCheck(new CheckResult("时间安排合理性", "pass", "日期逻辑与时间衔接正常，无明显冲突"));
        } else {
            List<String> messages = problems.stream().map(TimingIssue::message).toList();
            collector.addIssues(messages);
            String status = problems.stream().anyMatch(p -> p.severity() == Severity.FAIL) ? "fail" : "warning";
            collector.addCheck(new CheckResult("时间安排合理性", status, "存在时间问题：" + String.join("；", messages)));
        }
    }

    // ============================== 子检查 ==============================

    /**
     * 日期逻辑校验
     */
    private void checkDateValidity(JSONObject itinerary, JSONObject policy, List<TimingIssue> problems) {
        String depDateStr = itinerary.getString("departure_date");
        String retDateStr = itinerary.getString("return_date");
        if (depDateStr == null || retDateStr == null) {
            return;
        }
        LocalDate depDate = parseDate(depDateStr);
        LocalDate retDate = parseDate(retDateStr);
        if (depDate == null || retDate == null) {
            return;
        }

        // 返程日期必须晚于去程日期
        if (!retDate.isAfter(depDate)) {
            problems.add(new TimingIssue(Severity.FAIL,
                    "返程日期 " + retDateStr + " 不晚于去程日期 " + depDateStr));
        }

        // 去程日期不应是过去
        LocalDate today = LocalDate.now();
        if (depDate.isBefore(today)) {
            problems.add(new TimingIssue(Severity.WARNING,
                    "去程日期 " + depDateStr + " 早于今天 " + today));
        }

        // 住宿晚数应与日期区间匹配
        long expectedNights = retDate.toEpochDay() - depDate.toEpochDay();
        JSONArray days = itinerary.getJSONArray("days");
        if (days != null) {
            int actualNights = 0;
            for (int i = 0; i < days.size(); i++) {
                JSONObject day = days.getJSONObject(i);
                JSONObject hotel = day.getJSONObject("hotel");
                if (hotel != null) {
                    Integer nights = hotel.getInteger("nights");
                    if (nights != null) {
                        actualNights += nights;
                    }
                }
            }
            if (actualNights > 0 && actualNights != expectedNights) {
                problems.add(new TimingIssue(Severity.WARNING,
                        "住宿总晚数 " + actualNights + " 与日期区间 " + expectedNights + " 晚不匹配"));
            }
        }

        // 提前预订天数
        Integer advanceDays = policy.getInteger("advanceBookingDays");
        if (advanceDays != null && advanceDays > 0) {
            long bookingDays = depDate.toEpochDay() - today.toEpochDay();
            if (bookingDays < advanceDays) {
                problems.add(new TimingIssue(Severity.WARNING,
                        "距出发仅 " + bookingDays + " 天，政策要求提前 " + advanceDays + " 天预订"));
            }
        }
    }

    /**
     * 检查到达时间与首个活动之间的缓冲
     */
    private void checkArrivalBuffer(JSONObject day, String dateStr, List<TimingIssue> problems) {
        JSONObject transport = day.getJSONObject("outbound_transport");
        String arrivalTime = transport.getString("arrival");
        JSONArray activities = day.getJSONArray("activities");
        if (arrivalTime == null || activities == null || activities.isEmpty()) {
            return;
        }
        String activityTime = extractTime(activities.getString(0));
        if (activityTime == null) {
            return;
        }
        Integer buffer = minutesBetween(arrivalTime, activityTime);
        if (buffer == null) {
            problems.add(new TimingIssue(Severity.WARNING, dateStr + " 时间格式无法解析（到达 " + arrivalTime
                                                           + " / 活动 " + activityTime + "），未能校验到达缓冲"));
            return;
        }
        if (buffer >= 0 && buffer < MIN_ARRIVAL_BUFFER_MINUTES) {
            problems.add(new TimingIssue(Severity.WARNING, dateStr + " 到达时间 " + arrivalTime + " 与首个活动 " + activityTime
                                                           + " 之间缓冲不足 " + MIN_ARRIVAL_BUFFER_MINUTES + " 分钟（实际约 " + buffer + " 分钟）"));
        }
    }

    /**
     * 检查最后活动与返程出发时间是否冲突
     */
    private void checkReturnBuffer(JSONObject day, String dateStr, List<TimingIssue> problems) {
        JSONObject transport = day.getJSONObject("return_transport");
        String departureTime = transport.getString("departure");
        JSONArray activities = day.getJSONArray("activities");
        if (departureTime == null || activities == null || activities.isEmpty()) {
            return;
        }
        String activityTime = extractTime(activities.getString(activities.size() - 1));
        if (activityTime == null) {
            return;
        }
        Integer buffer = minutesBetween(activityTime, departureTime);
        if (buffer == null) {
            problems.add(new TimingIssue(Severity.WARNING, dateStr + " 时间格式无法解析（活动 " + activityTime
                                                           + " / 返程 " + departureTime + "），未能校验返程缓冲"));
            return;
        }
        if (buffer < 0) {
            problems.add(new TimingIssue(Severity.FAIL, dateStr + " 返程出发时间 " + departureTime
                                                        + " 早于最后活动时间 " + activityTime + "，存在时间冲突"));
        } else if (buffer < MIN_RETURN_BUFFER_MINUTES) {
            problems.add(new TimingIssue(Severity.WARNING, dateStr + " 返程前缓冲时间仅 " + buffer + " 分钟，建议预留至少 "
                                                           + MIN_RETURN_BUFFER_MINUTES + " 分钟"));
        }
    }

    /**
     * 检查中转时间是否充裕（过短有误机风险，过长则浪费时间）
     */
    private void checkTransferTime(JSONObject day, String dateStr, List<TimingIssue> problems) {
        JSONObject transport = day.getJSONObject("outbound_transport");
        JSONObject transfer = transport.getJSONObject("transfer");
        if (transfer == null) {
            return;
        }
        int transferMinutes = transfer.getIntValue("transfer_minutes", 0);
        if (transferMinutes <= 0) {
            return;
        }
        boolean isIntl = Boolean.TRUE.equals(transfer.getBoolean("international"));
        int minTransfer = isIntl ? MIN_INTL_TRANSFER_MINUTES : MIN_DOMESTIC_TRANSFER_MINUTES;
        if (transferMinutes < minTransfer) {
            problems.add(new TimingIssue(Severity.WARNING, dateStr + " 中转时间仅 " + transferMinutes + " 分钟，"
                                                           + (isIntl ? "国际" : "国内") + "中转建议 ≥ " + minTransfer + " 分钟"));
        } else if (transferMinutes > MAX_TRANSFER_MINUTES) {
            problems.add(new TimingIssue(Severity.WARNING, dateStr + " 中转时间长达 " + transferMinutes + " 分钟（"
                                                           + transferMinutes / 60 + " 小时 " + transferMinutes % 60 + " 分钟），等待时间过长，建议缩短"));
        }
    }

    /**
     * 检查红眼航班（深夜出发、凌晨到达，仅限航班）
     */
    private void checkRedEyeFlight(JSONObject transport, String label, String dateStr,
                                   List<TimingIssue> problems) {
        if (!"flight".equals(transport.getString("type"))) {
            return;
        }
        String depTime = transport.getString("departure");
        String arrTime = transport.getString("arrival");
        if (depTime == null || arrTime == null) {
            return;
        }
        try {
            LocalTime dep = LocalTime.parse(depTime, TIME_FORMAT);
            LocalTime arr = LocalTime.parse(arrTime, TIME_FORMAT);
            // 出发 ≥ 22:00 且 到达 ≤ 06:00 → 红眼航班
            if (dep.getHour() >= RED_EYE_DEPARTURE_HOUR && arr.getHour() <= RED_EYE_ARRIVAL_HOUR) {
                problems.add(new TimingIssue(Severity.WARNING,
                        dateStr + " " + label + "航班 " + depTime + "→" + arrTime + " 为红眼航班，建议调整"));
            }
        } catch (DateTimeParseException e) {
            // 时间格式无法解析，跳过红眼检测
        }
    }

    // ============================== 工具方法 ==============================

    /**
     * 解析 yyyy-MM-dd 格式日期
     */
    private LocalDate parseDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 从活动描述中提取时间，如 "09:30 会议" -> "09:30"
     */
    private static String extractTime(String activity) {
        if (activity == null) {
            return null;
        }
        String[] parts = activity.trim().split("\\s+");
        return (parts.length > 0 && parts[0].matches("\\d{1,2}:\\d{2}")) ? parts[0] : null;
    }

    /**
     * 计算两个 HH:mm 之间的分钟差；格式无法解析时返回 null，由调用方按"未知"处理，不得视为缓冲充足
     */
    private static Integer minutesBetween(String fromTime, String toTime) {
        try {
            LocalTime from = LocalTime.parse(fromTime, TIME_FORMAT);
            LocalTime to = LocalTime.parse(toTime, TIME_FORMAT);
            return (int) Duration.between(from, to).toMinutes();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
