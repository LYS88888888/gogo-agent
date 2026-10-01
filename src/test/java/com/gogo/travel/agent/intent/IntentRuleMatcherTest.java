package com.gogo.travel.agent.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IntentRuleMatcher} L1 规则命中率与精度测试。
 *
 * <p>用例全部取自企业差旅真实口语化表达，并对齐
 * {@code IntentRouterKnowledgeConfig} 的 L2 种子语料。测试分三类：</p>
 * <ul>
 *   <li>expectMatch：清晰模板化表达，L1 应命中且分到正确意图类别；</li>
 *   <li>expectAgent：只关心路由结果的表达（如指代式预订"预订这个"），只校验落到正确的
 *       目标子智能体即可，具体分到组内哪个意图类别不影响路由；</li>
 *   <li>expectEmpty：信息不足/开放式问法，L1 应保守放行（返回 empty），交给 L2/L3。</li>
 * </ul>
 *
 * <p>测试收集全部失败用例后一次性断言，便于查看整体命中情况。</p>
 *
 */
@DisplayName("IntentRuleMatcher L1 规则命中率测试")
class IntentRuleMatcherTest {

    private final IntentRuleMatcher matcher = new IntentRuleMatcher();

    @Test
    @DisplayName("清晰表达应命中正确意图类别")
    void shouldHitExpectedCategory() {
        List<Case> cases = new ArrayList<>();

        // —— 寒暄 GREETING ——
        expect(cases, IntentCategory.GREETING, "你好", "您好", "在吗？", "hi", "hello",
                "早上好", "下午好，请问在吗？", "嗨~");

        // —— 报销 REIMBURSEMENT ——
        expect(cases, IntentCategory.REIMBURSEMENT,
                "帮我报销这张机票", "报销", "识别一下发票", "生成报销单",
                "帮我报一下这次出差的费用", "提交报销", "差旅报销", "帮我报个销");

        // —— 政策/标准 POLICY_QUERY（含与报销的跨类边界）——
        expect(cases, IntentCategory.POLICY_QUERY,
                "差旅政策是什么", "餐标是多少", "出差住宿标准", "机票预订规定",
                "签证怎么办", "入境政策", "差旅管理规定",
                "报销政策", "报销标准是多少", "差标是多少", "酒店住宿标准是多少");

        // —— 审批查询 APPROVAL_QUERY ——
        expect(cases, IntentCategory.APPROVAL_QUERY,
                "我的审批到哪了", "查一下审批进度", "我的出差申请审批通过了吗",
                "审批状态", "审批结果怎么样", "领导审批了吗", "审批到哪个环节了");

        // —— 取消 TRAVEL_CANCEL ——
        expect(cases, IntentCategory.TRAVEL_CANCEL,
                "取消出差", "撤回出差申请", "取消审批", "取消我的差旅申请",
                "这次不去了", "不出差了");

        // —— 修改 TRAVEL_MODIFY ——
        expect(cases, IntentCategory.TRAVEL_MODIFY,
                "修改出差申请", "改一下出差日期", "修改我的差旅申请",
                "出差目的地改了", "把出差日期改一下");

        // —— 已有差旅单查询 TRAVEL_ORDER_QUERY ——
        // 后三条为“歧义名词 + 查询语境”回归：出差安排/差旅行程 搭配 我的/最近/有…吗/看 仍应归查询。
        expect(cases, IntentCategory.TRAVEL_ORDER_QUERY,
                "查一下我的差旅行程", "我的出差单状态", "查看我的出差安排",
                "查一下这个差旅订单", "我本周有出差安排吗？", "差旅单详情", "我上次的差旅订单",
                "查一下我最近的出差安排", "我下周有出差安排吗", "看看我的差旅行程");

        // —— 新建差旅申请 TRAVEL_APPLICATION ——
        // 仅显式申请动作才在 L1 短路；“安排出差行程”这类笼统措辞见 shouldDeferAmbiguousToLowerLayers。
        expect(cases, IntentCategory.TRAVEL_APPLICATION,
                "我要出差", "帮我提交一个出差申请", "提个出差单", "发起差旅申请",
                "我下周要去上海拜访客户，帮我提个出差申请", "报备出差", "申请去北京出差");

        // —— 行程规划 ITINERARY_PLANNING ——
        // 注：「我下周去北京出差，帮我做一下行程规划」已移入多意图守卫用例——
        // 前半句命中差旅申请（Manage）、后半句命中行程规划（Plan），跨子智能体，L1 保守放行 L3。
        // “安排…行程”属弱信号，只有不含「出差/差旅/查/我的」的纯目的地行程才在 L1 短路。
        expect(cases, IntentCategory.ITINERARY_PLANNING,
                "帮我规划行程", "安排一下杭州的行程",
                "出差行程方案", "帮我做一份差旅行程", "规划一下这几天的安排");

        // —— 机票查询 FLIGHT_SEARCH ——
        expect(cases, IntentCategory.FLIGHT_SEARCH,
                "查机票", "北京到杭州的航班", "帮我查一下航班",
                "看看有没有去上海的机票", "订机票", "航班时刻表");

        // —— 火车查询 TRAIN_SEARCH ——
        expect(cases, IntentCategory.TRAIN_SEARCH,
                "查火车票", "北京到上海的高铁", "动车票", "帮我查一下火车",
                "看一下车次", "高铁票查询");

        // —— 酒店查询 HOTEL_SEARCH ——
        expect(cases, IntentCategory.HOTEL_SEARCH,
                "查酒店", "杭州的酒店", "附近酒店", "帮我订个酒店",
                "出差住宿推荐", "我住哪里比较好");

        expect(cases, IntentCategory.TRAVEL_MODIFY,
                "修改一下，我的出差申请");

        // —— 通用信息 GENERAL_INFO ——
        expect(cases, IntentCategory.GENERAL_INFO,
                "今天北京天气怎么样", "怎么去机场", "上海地铁线路",
                "杭州有什么新闻", "交通路况怎么样");

        // —— 景点 ATTRACTIONS_QUERY ——
        expect(cases, IntentCategory.ATTRACTIONS_QUERY,
                "杭州有什么好玩的", "景点推荐", "上海旅游景点",
                "北京有哪些必去的景点", "当地有什么好玩的地方");

        // —— 跨类边界陷阱（分错组会导致路由到错误子智能体）——
        expect(cases, IntentCategory.FLIGHT_SEARCH, "你好，帮我订机票");
        expect(cases, IntentCategory.BOOKING, "取消酒店预订", "改签机票", "退票");
        // 改签是订单操作，不得被“(出差|差旅).*改”误吞成差旅单修改
        expect(cases, IntentCategory.BOOKING, "帮我改签下出差的机票");
        // 「预订/预定」同音混写回归：明确下订诉求不得被机/酒/火查询的品类关键词抢占
        expect(cases, IntentCategory.BOOKING,
                "帮我9.10日的行程预定一下机票", "帮我预订一下机票",
                "帮我预定个酒店", "帮我预定下周去上海的火车票");

        runCategoryCases(cases);
    }

    @Test
    @DisplayName("分组内歧义只需命中正确目标子智能体")
    void shouldHitExpectedTargetAgent() {
        List<AgentCase> cases = new ArrayList<>();

        // 指代式预订/确认下单等表达统一归 BOOKING → BookingAgent
        // （机/火/酒查询规则已用"预订这个/订下来"等负向词把这类表达让渡给 BOOKING 兜底规则）
        expectAgent(cases, "BookingAgent",
                "预订这个", "帮我把这个订下来", "确认下单", "帮我预订这个航班");

        // 各类差旅单操作同属 ItineraryManageAgent
        expectAgent(cases, "ItineraryManageAgent", "帮我看看这个出差申请");

        runAgentCases(cases);
    }

    @Test
    @DisplayName("信息不足的开放式问法 L1 应保守放行")
    void shouldDeferAmbiguousToLowerLayers() {
        List<String> shouldBeEmpty = List.of(
                "随便问问", "你能做什么", "有什么功能", "帮助", "这个怎么说",
                // 歧义名词“出差安排”不带查询语境，实为办理请求（新建申请 or 规划行程），
                // L1 无法裁定，必须放行给 L2/L3，不得短路成“查已有差旅单”
                "帮我处理这次出差安排",
                "我本周五到周日，去三亚参加海天盛筵，帮我处理这次出差安排",
                "帮我弄一下出差安排",
                // “安排+出差行程”字面像规划、实则多为新建差旅申请，需 L3 结合上下文
                // （是否已有差旅单/审批）裁定，L1 不得短路成行程规划
                "我本周五到周日，去三亚参加海天盛筵，请帮我安排出差行程。",
                "请帮我安排出差行程",
                "帮我安排一下这次差旅的行程");

        List<String> failures = new ArrayList<>();
        for (String text : shouldBeEmpty) {
            Optional<IntentRecognitionResult> result = matcher.match(text);
            if (result.isPresent()) {
                failures.add(String.format("「%s」期望 L1 放行(empty)，实际命中=%s",
                        text, result.get().getPrimary()));
            }
        }
        assertTrue(failures.isEmpty(), buildReport("过度命中(应放行却命中)", failures));
    }

    @Test
    @DisplayName("跨子智能体的多意图复合句应判为 AMBIGUOUS 放行")
    void shouldFlagCrossAgentCompoundAsAmbiguous() {
        // 各子句分别命中不同目标子智能体的意图，L1 不应短路成单意图
        List<String> compound = List.of(
                // FLIGHT_SEARCH(Plan) + ATTRACTIONS_QUERY(Info)
                "帮我查下去北京的机票，看看有什么好玩的",
                // POLICY_QUERY(Info) + REIMBURSEMENT(Reimbursement)，
                // 后半句的报销意图不应被前半句的"政策"负向词挡掉
                "查下差旅政策，帮我把上次的发票报销了",
                // ITINERARY_PLANNING(Plan) + APPROVAL_QUERY(Manage)
                "帮我规划一下上海的行程，再查下审批进度",
                // FLIGHT_SEARCH(Plan) + REIMBURSEMENT(Reimbursement)，连词"和"切分
                "帮我订机票和报销发票",
                // TRAVEL_APPLICATION(Manage) + ITINERARY_PLANNING(Plan)：
                // 前半句可能是上下文也可能是独立意图，L1 无法裁决，保守交给 L3
                "我下周去北京出差，帮我做一下行程规划");

        List<String> failures = new ArrayList<>();
        for (String text : compound) {
            IntentRuleMatcher.Outcome outcome = matcher.evaluate(text);
            if (outcome.verdict() != IntentRuleMatcher.Verdict.AMBIGUOUS) {
                failures.add(String.format("「%s」期望=AMBIGUOUS，实际=%s(%s)",
                        text, outcome.verdict(),
                        outcome.result() == null ? outcome.ambiguousCategories() : outcome.result().getPrimary()));
            }
        }
        assertTrue(failures.isEmpty(), buildReport("复合句未被多意图守卫拦截", failures));
    }

    @Test
    @DisplayName("同一子智能体内的复合句仍可短路（组内由同一 Agent 处理）")
    void shouldStillHitWhenCompoundStaysInSameAgent() {
        // 机票 + 酒店同属 ItineraryPlanAgent，直跳后子智能体可在一次会话内处理两件事
        IntentRuleMatcher.Outcome outcome = matcher.evaluate("帮我订机票和酒店");
        assertTrue(outcome.verdict() == IntentRuleMatcher.Verdict.HIT,
                "「帮我订机票和酒店」期望 HIT，实际=" + outcome.verdict());
        assertTrue("ItineraryPlanAgent".equals(outcome.result().getPrimary().getDefaultTargetAgent()),
                "目标子智能体应为 ItineraryPlanAgent，实际=" + outcome.result().getPrimary().getDefaultTargetAgent());

        // 寒暄 + 单任务是典型单意图，寒暄不参与歧义判定
        IntentRuleMatcher.Outcome greeting = matcher.evaluate("你好，帮我订机票");
        assertTrue(greeting.verdict() == IntentRuleMatcher.Verdict.HIT
                        && greeting.result().getPrimary() == IntentCategory.FLIGHT_SEARCH,
                "「你好，帮我订机票」期望 HIT(FLIGHT_SEARCH)，实际=" + greeting.verdict());
    }

    // ----------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------

    private void runCategoryCases(List<Case> cases) {
        List<String> failures = new ArrayList<>();
        for (Case c : cases) {
            Optional<IntentRecognitionResult> result = matcher.match(c.text);
            if (result.isEmpty()) {
                failures.add(String.format("「%s」期望=%s，实际=未命中(L1 miss)", c.text, c.expected));
            } else if (result.get().getPrimary() != c.expected) {
                failures.add(String.format("「%s」期望=%s，实际=%s",
                        c.text, c.expected, result.get().getPrimary()));
            }
        }
        assertTrue(failures.isEmpty(), buildReport("意图类别不匹配", failures));
    }

    private void runAgentCases(List<AgentCase> cases) {
        List<String> failures = new ArrayList<>();
        for (AgentCase c : cases) {
            Optional<IntentRecognitionResult> result = matcher.match(c.text);
            if (result.isEmpty()) {
                failures.add(String.format("「%s」期望目标=%s，实际=未命中(L1 miss)", c.text, c.expectedAgent));
            } else {
                String actual = result.get().getPrimary().getDefaultTargetAgent();
                if (!c.expectedAgent.equals(actual)) {
                    failures.add(String.format("「%s」期望目标=%s，实际=%s(%s)",
                            c.text, c.expectedAgent, actual, result.get().getPrimary()));
                }
            }
        }
        assertTrue(failures.isEmpty(), buildReport("目标子智能体不匹配", failures));
    }

    private static void expect(List<Case> cases, IntentCategory expected, String... texts) {
        for (String text : texts) {
            cases.add(new Case(text, expected));
        }
    }

    private static void expectAgent(List<AgentCase> cases, String expectedAgent, String... texts) {
        for (String text : texts) {
            cases.add(new AgentCase(text, expectedAgent));
        }
    }

    private static String buildReport(String title, List<String> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("，共 ").append(failures.size()).append(" 条失败：\n");
        for (String f : failures) {
            sb.append("  - ").append(f).append('\n');
        }
        return sb.toString();
    }

    private record Case(String text, IntentCategory expected) {
    }

    private record AgentCase(String text, String expectedAgent) {
    }
}
