package com.gogo.travel.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.tools.model.*;
import com.gogo.travel.business.order.entity.TravelOrder;
import com.gogo.travel.business.order.entity.TravelOrderStatus;
import com.gogo.travel.business.order.repo.TravelOrderRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 行程冲突检测工具集。
 *
 * <p>提供 {@code check_travel_order_conflicts} 工具，覆盖两类检查：</p>
 * <ol>
 *   <li><b>同一员工时间重叠</b>：检测日期范围重叠的差旅单，区分同城（重复提交，LOW）和跨城（物理不可能，HIGH）。</li>
 *   <li><b>跨城市交通衔接合理性</b>：检测相邻差旅单之间的城市衔接，分为"同日衔接时间不足"（MEDIUM/HIGH）和"路径断裂"（MEDIUM）。</li>
 * </ol>
 *
 * <p>该工具为"建议"性质——是否真的阻断提交由 MasterAgent / LLM 决定；
 * ItineraryManageAgent 仍可在告知用户冲突后由用户确认后继续提交。</p>
 *
 */
@Component
public class TravelOrderConflictTools {

    private static final Logger logger = LoggerFactory.getLogger(TravelOrderConflictTools.class);

    /** 同日跨城衔接：所需分钟数超过该值即视为“时间紧张” */
    private static final int SAME_DAY_TIGHT_MINUTES = 8 * 60;
    
    /** 相邻日衔接：跨城交通不超过该分钟数即认为 1 天足够，不再告警（与同日阈值语义独立，可单独调整） */
    private static final int ADJACENT_DAY_MIN_TRANSIT_MINUTES = 8 * 60;
    
    /** 视为“绝对不可能”的天数：同日衔接但所需分钟超过该值 */
    private static final int SAME_DAY_IMPOSSIBLE_MINUTES = 24 * 60;

    @Autowired
    private TravelOrderRepository travelOrderRepository;
    @Autowired
    private CityTransitTimeService cityTransitTimeService;

    // ─────────────────────────────────────────────────────────────────────────
    // 行程冲突检测
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(name = "check_travel_order_conflicts",
          description = "检查用户当前要提交/修改的差旅单是否与已有生效中差旅单（DRAFT/SUBMITTED/APPROVED）存在冲突。"
                  + "覆盖两类检查：① 同一员工时间重叠（同城=LOW重复，跨城=HIGH物理不可能）；"
                  + "② 跨城市交通衔接合理性（同日衔接时间不足=TRANSIT_TOO_TIGHT；跨城但未衔接=DISCONNECTED_ROUTE）。"
                  + "返回 JSON 结构 {has_conflict, total_conflicts, conflicts:[...], summary}。"
                  + "建议在每次 submit_travel_approval 之前、以及修改差旅单的出发城市/目的地/日期时调用。")
    public String checkTravelOrderConflicts(
            AgentSessionContext sessionCtx,
            @ToolParam(name = "departure_city", description = "候选差旅单的出发城市（城市级名称，如 北京/上海，不支持区/县）") String departureCity,
            @ToolParam(name = "destination", description = "候选差旅单的目的地城市（城市级名称，如 杭州/深圳，不支持区/县）") String destination,
            @ToolParam(name = "departure_date", description = "候选差旅单的出发日期，YYYY-MM-DD") String departureDate,
            @ToolParam(name = "return_date", description = "候选差旅单的返回日期，YYYY-MM-DD") String returnDate,
            @ToolParam(name = "exclude_order_id", description = "需要排除的差旅单ID（修改场景下排除自身），可选", required = false) String excludeOrderId) {

        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][check_travel_order_conflicts] userId={}, {}->{}, {}-{}, exclude={}",
                userId, departureCity, destination, departureDate, returnDate, excludeOrderId);

        // ── 入参校验 ──────────────────────────────────────────────────────────
        List<String> errors = new ArrayList<>();
        if (isBlank(userId))         {
            errors.add("user_id 不能为空");
        }
        if (isBlank(departureCity))  {
            errors.add("departure_city（出发城市）不能为空");
        }
        if (isBlank(destination))    {
            errors.add("destination（目的地）不能为空");
        }
        if (!isValidDate(departureDate)) {
            errors.add("departure_date（出发日期）格式错误或为空，需 YYYY-MM-DD");
        }
        if (!isValidDate(returnDate))    {
            errors.add("return_date（返回日期）格式错误或为空，需 YYYY-MM-DD");
        }
        if (!errors.isEmpty()) {
            return JSON.toJSONString(new ConflictReport() {{
                setHasConflict(false);
                setSummary("入参校验失败：" + String.join("；", errors));
            }});
        }

        LocalDate depDate;
        LocalDate retDate;
        try {
            depDate = LocalDate.parse(departureDate);
            retDate = LocalDate.parse(returnDate);
        } catch (DateTimeParseException e) {
            return JSON.toJSONString(new ConflictReport() {{
                setHasConflict(false);
                setSummary("日期解析失败：" + e.getMessage());
            }});
        }
        if (depDate.isAfter(retDate)) {
            return JSON.toJSONString(new ConflictReport() {{
                setHasConflict(false);
                setSummary("出发日期（" + departureDate + "）晚于返回日期（" + returnDate + "），请先修正日期。");
            }});
        }

        // ── 查询已有生效中差旅单（日期有重叠） ─────────────────────────────
        Set<TravelOrderStatus> activeStatuses = Set.of(
                TravelOrderStatus.DRAFT, TravelOrderStatus.SUBMITTED, TravelOrderStatus.APPROVED);
        List<TravelOrder> candidates = travelOrderRepository.findActiveByUserIdAndDateRange(
                userId, activeStatuses, depDate.minusDays(1).toString(), retDate.plusDays(1).toString());
        // 查询窗口已各向外扩 1 天：严格重叠查询会漏掉“次日衔接”的相邻日差旅单，导致路径断裂检测失效

        // 排除自身（修改场景）
        if (!isBlank(excludeOrderId)) {
            candidates.removeIf(o -> excludeOrderId.equals(o.getOrderId()));
        }

        // ── 冲突检测 ────────────────────────────────────────────────────────
        List<ConflictItem> conflicts = new ArrayList<>();
        for (TravelOrder existing : candidates) {
            if (!isValidDate(existing.getDepartureDate()) || !isValidDate(existing.getReturnDate())) {
                // 数据异常，跳过但记录日志
                logger.warn("[TOOL][check_travel_order_conflicts] 跳过数据异常的差旅单 orderId={}", existing.getOrderId());
                continue;
            }
            detectConflictsFor(existing, departureCity, destination, depDate, retDate, conflicts);
        }

        // 按严重等级降序：HIGH > MEDIUM > LOW
        conflicts.sort(Comparator.comparingInt((ConflictItem c) -> c.getSeverity().rank())
                .thenComparing(c -> c.getOrderId() == null ? "" : c.getOrderId()));

        ConflictReport report = new ConflictReport();
        report.setConflicts(conflicts);
        report.setTotalConflicts(conflicts.size());
        report.setHasConflict(!conflicts.isEmpty());
        report.setSummary(buildSummary(conflicts, departureCity, destination, departureDate, returnDate));

        logger.info("[TOOL][check_travel_order_conflicts] userId={} 命中冲突 {} 条", userId, conflicts.size());
        return JSON.toJSONString(report);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 单条已有差旅单 vs 候选差旅单的冲突检测
    // ─────────────────────────────────────────────────────────────────────────

    private void detectConflictsFor(TravelOrder existing,
                                    String candidateDepCity,
                                    String candidateDest,
                                    LocalDate candidateDep,
                                    LocalDate candidateRet,
                                    List<ConflictItem> out) {
        LocalDate existingDep = LocalDate.parse(existing.getDepartureDate());
        LocalDate existingRet = LocalDate.parse(existing.getReturnDate());
        String existingDepCity = existing.getDepartureCity();
        String existingDest = existing.getDestination();

        // ① 同日交接（必须先于重叠判断）：已有单恰在候选出发日当天结束，属于“同一天先结束再出发”，
        //    可行性由跨城交通时长判断；若先判区间重叠（端点相接也算重叠）会把可行的同日交接误报为 HIGH。
        //    双重等式同时成立意味着两单同为同一天单日行程，交由重叠分支处理
        if (existingRet.isEqual(candidateDep) && !existingDep.isEqual(candidateRet)) {
            addSameDayTransitConflict(existing, existingDest, candidateDepCity, out);
            return;
        }

        // ② 反向同日交接：候选在已有单出发日当天结束，需从候选目的地赶到已有单出发城市
        if (existingDep.isEqual(candidateRet) && !existingRet.isEqual(candidateDep)) {
            addSameDayTransitConflict(existing, candidateDest, existingDepCity, out);
            return;
        }

        // ③ 日期重叠：[existingDep, existingRet] ∩ [candidateDep, candidateRet] 非空
        if (!existingRet.isBefore(candidateDep) && !existingDep.isAfter(candidateRet)) {
            addOverlapConflict(existing, existingDep, existingRet, existingDepCity, existingDest,
                    candidateDep, candidateRet, candidateDepCity, candidateDest, out);
            return;
        }

        // ④ 次日衔接：existingRet == candidateDep - 1 天（昨天结束、今天出发）
        if (existingRet.plusDays(1).isEqual(candidateDep)) {
            addAdjacentDayConflict(existing, "departure", existingDep, existingRet, existingDepCity, existingDest,
                    candidateDep, candidateRet, candidateDepCity, candidateDest, out);
            return;
        }

        // ⑤ 反向次日衔接：existingDep == candidateRet + 1 天
        if (existingDep.minusDays(1).isEqual(candidateRet)) {
            addAdjacentDayConflict(existing, "return", existingDep, existingRet, existingDepCity, existingDest,
                    candidateDep, candidateRet, candidateDepCity, candidateDest, out);
        }
    }

    /**
     * 添加"日期完全重叠"冲突。
     * <p>同城市=LOW 重复提交；不同城市=HIGH 物理不可能。</p>
     */
    private void addOverlapConflict(TravelOrder existing,
                                    LocalDate existingDep, LocalDate existingRet,
                                    String existingDepCity, String existingDest,
                                    LocalDate candidateDep, LocalDate candidateRet,
                                    String candidateDepCity, String candidateDest,
                                    List<ConflictItem> out) {
        boolean sameCity = isSameCity(existingDepCity, existingDest, candidateDepCity, candidateDest);
        ConflictItem item = new ConflictItem();
        item.setOrderId(existing.getOrderId());
        item.setOrderSummary(buildOrderSummary(existing));
        if (sameCity) {
            item.setType(ConflictType.TIME_OVERLAP_SAME_CITY);
            item.setSeverity(ConflictSeverity.LOW);
            item.setDescription(String.format(
                    "已有差旅单的时间段（%s ~ %s，%s ↔ %s）与本次完全重叠，属于同城市重复提交。",
                    existingDep, existingRet, existingDepCity, existingDest));
            item.setSuggestion("可继续提交，但建议先取消或调整其中一张差旅单，避免重复审批。");
        } else {
            item.setType(ConflictType.TIME_OVERLAP_DIFF_CITY);
            item.setSeverity(ConflictSeverity.HIGH);
            // 出发地/目的地恰好互换：可能是同一趟出行的往返拆单，在文案中点破供用户自判
            boolean reversed = isSameCityValue(existingDepCity, candidateDest)
                    && isSameCityValue(existingDest, candidateDepCity);
            String description = String.format(
                    "已有差旅单（%s ~ %s，%s ↔ %s）与本次（%s ~ %s，%s → %s）在时间段上重叠，"
                            + "但目的地不同，物理上不可能同时身处两地。",
                    existingDep, existingRet, existingDepCity, existingDest,
                    candidateDep, candidateRet, candidateDepCity, candidateDest);
            if (reversed) {
                description += "注意：两张差旅单方向恰好相反，若属同一趟出行的往返拆单，请确认是否重复提交。";
            }
            item.setDescription(description);
            item.setSuggestion("请调整本次差旅的出发日期或返回日期，使其与已有差旅单不重叠；"
                    + "如确有需要请先取消/修改已有差旅单。");
        }
        out.add(item);
    }

    /**
     * 添加"同日衔接"冲突——同一天需要完成从 endCity 到 startCity 的跨城交通。
     *
     * @param endCity   当天结束行程所在城市（正向=已有单目的地；反向=候选单目的地）
     * @param startCity 当天出发行程的出发城市（正向=候选单出发地；反向=已有单出发地）
     */
    private void addSameDayTransitConflict(TravelOrder existing,
                                           String endCity, String startCity,
                                           List<ConflictItem> out) {
        int minutes = cityTransitTimeService.estimateMinutes(endCity, startCity);
        ConflictItem item = new ConflictItem();
        item.setOrderId(existing.getOrderId());
        item.setOrderSummary(buildOrderSummary(existing));
        item.setType(ConflictType.TRANSIT_TOO_TIGHT);
        if (minutes <= 0) {
            // 同城，无冲突
            return;
        }
        if (minutes > SAME_DAY_IMPOSSIBLE_MINUTES) {
            item.setSeverity(ConflictSeverity.HIGH);
            item.setDescription(String.format(
                    "同日需要从 %s 前往 %s，最短衔接时间约 %d 小时，超出当日可行范围，物理上无法完成。",
                    endCity, startCity, minutes / 60));
            item.setSuggestion("请将出发日期至少延后 1 天，或调整目的地。");
        } else if (minutes > SAME_DAY_TIGHT_MINUTES) {
            item.setSeverity(ConflictSeverity.MEDIUM);
            item.setDescription(String.format(
                    "同日需要从 %s 前往 %s，估算最短衔接时间约 %d 小时，时间非常紧张。",
                    endCity, startCity, minutes / 60));
            item.setSuggestion("建议改为次日出发，或选择更早的航班/高铁以预留缓冲时间。");
        } else {
            item.setSeverity(ConflictSeverity.MEDIUM);
            item.setDescription(String.format(
                    "同日需要从 %s 前往 %s，估算衔接时间约 %d 小时。",
                    endCity, startCity, minutes / 60));
            item.setSuggestion("衔接可行但偏紧，建议选择早班交通，并预留 1~2 小时缓冲。");
        }
        out.add(item);
    }

    /**
     * 添加"次日衔接 / 路径断裂"冲突。
     *
     * @param side "departure" — 候选出发前一天结束；"return" — 候选返回后第一天开始
     */
    private void addAdjacentDayConflict(TravelOrder existing,
                                        String side,
                                        LocalDate existingDep, LocalDate existingRet,
                                        String existingDepCity, String existingDest,
                                        LocalDate candidateDep, LocalDate candidateRet,
                                        String candidateDepCity, String candidateDest,
                                        List<ConflictItem> out) {
        // 仅检测跨城衔接是否合理：已有差旅的"结束城市"必须 = 候选差旅的"出发城市"
        String endCity;
        String startCity;
        String relation;
        if ("departure".equals(side)) {
            endCity = existingDest;
            startCity = candidateDepCity;
            relation = String.format("已有差旅在 %s 结束于 %s，候选差旅在次日（%s）从 %s 出发",
                    existingRet, existingDest, candidateDep, candidateDepCity);
        } else {
            endCity = candidateDest;
            startCity = existingDepCity;
            relation = String.format("候选差旅在 %s 结束于 %s，已有差旅在次日（%s）从 %s 出发",
                    candidateRet, candidateDest, existingDep, existingDepCity);
        }

        if (isSameCityValue(endCity, startCity)) {
            // 城市一致，无需判断
            return;
        }

        int minutes = cityTransitTimeService.estimateMinutes(endCity, startCity);
        if (minutes <= ADJACENT_DAY_MIN_TRANSIT_MINUTES) {
            // 1 天时间够跨城衔接
            return;
        }

        ConflictItem item = new ConflictItem();
        item.setOrderId(existing.getOrderId());
        item.setOrderSummary(buildOrderSummary(existing));
        item.setType(ConflictType.DISCONNECTED_ROUTE);
        item.setSeverity(ConflictSeverity.MEDIUM);
        item.setDescription(String.format(
                "%s，但跨城交通至少需要 %d 小时，仅 1 天时间衔接偏紧，"
                        + "存在误机/赶不上高铁的风险。",
                relation, minutes / 60));
        item.setSuggestion("建议在两段行程之间留出 1~2 天缓冲，或将其中一段改为同城行程。");
        out.add(item);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────────────────────

    private static String buildOrderSummary(TravelOrder o) {
        return String.format("%s → %s，%s ~ %s（%s）",
                safe(o.getDepartureCity()),
                safe(o.getDestination()),
                safe(o.getDepartureDate()),
                safe(o.getReturnDate()),
                o.getStatus() == null ? "-" : o.getStatus().getCode());
    }

    private static String buildSummary(List<ConflictItem> conflicts,
                                       String depCity, String dest, String depDate, String retDate) {
        if (conflicts.isEmpty()) {
            return String.format("已检查用户已有差旅单，未发现与 %s → %s（%s ~ %s）存在冲突。",
                    depCity, dest, depDate, retDate);
        }
        long high = conflicts.stream().filter(c -> c.getSeverity() == ConflictSeverity.HIGH).count();
        long medium = conflicts.stream().filter(c -> c.getSeverity() == ConflictSeverity.MEDIUM).count();
        long low = conflicts.stream().filter(c -> c.getSeverity() == ConflictSeverity.LOW).count();
        return String.format("命中 %d 条冲突（HIGH=%d, MEDIUM=%d, LOW=%d），请逐条阅读 description 与 suggestion。",
                conflicts.size(), high, medium, low);
    }

    private static boolean isSameCity(String depCityA, String destA, String depCityB, String destB) {
        // 视作"同城市重复"：出发地与目的地都成对相同（双向一致）
        return isSameCityValue(depCityA, depCityB) && isSameCityValue(destA, destB);
    }

    private static boolean isSameCityValue(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String na = a.trim();
        String nb = b.trim();
        if (na.endsWith("市") && na.length() > 1) {
            na = na.substring(0, na.length() - 1);
        }
        if (nb.endsWith("市") && nb.length() > 1) {
            nb = nb.substring(0, nb.length() - 1);
        }
        return na.equalsIgnoreCase(nb);
    }

    private static boolean isValidDate(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 计算日期差（天数） */
    @SuppressWarnings("unused")
    private static long daysBetween(LocalDate a, LocalDate b) {
        return ChronoUnit.DAYS.between(a, b);
    }
}
