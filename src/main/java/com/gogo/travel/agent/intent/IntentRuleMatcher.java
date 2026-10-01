package com.gogo.travel.agent.intent;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * L1 规则/关键词匹配器。
 *
 * <p>针对意图清晰、表达高度模板化的高频场景（寒暄、报销、政策查询、查询类等），
 * 在内存中做关键词/正则匹配，命中后立即返回 {@link IntentRecognitionResult}，
 * 目标延迟 &lt; 50ms。</p>
 *
 * <p>求值策略：对全部规则求值后再裁决（而非首中即返），以便识别"多类命中"——
 * 当命中的规则类别横跨 <b>两个及以上目标子智能体</b> 时，说明输入大概率是多意图复合句
 * （如"查下机票，顺便看看有什么好玩的"），L1 保守放行（{@link Verdict#AMBIGUOUS}），
 * 交给 L3 LLM 判定 {@code multi_intent}；同一目标子智能体内的多类命中仍按规则优先级
 * （List 顺序）取首个，因为组内误判不影响路由，且规则顺序本身编码了消歧语义。</p>
 *
 * <p>注意：L1 应当保守，宁可漏判也不要误判；不确定时直接放行让 L2/L3 处理。</p>
 *
 */
@Component
public class IntentRuleMatcher {

    /**
     * L1 匹配裁决：单类命中 / 多类歧义（疑似多意图）/ 未命中。
     */
    public enum Verdict {
        /**
         * 单类命中（或多类命中但同属一个目标子智能体），可安全短路。
         */
        HIT,
        /**
         * 子句级多类命中且横跨多个目标子智能体，疑似多意图复合句，应放行 L3（并跳过 L2）。
         */
        AMBIGUOUS,
        /**
         * 未命中任何规则，交给 L2/L3。
         */
        MISS
    }

    /**
     * L1 匹配结局。{@code result} 仅在 {@link Verdict#HIT} 时非空；
     * {@code ambiguousCategories} 仅在 {@link Verdict#AMBIGUOUS} 时非空，用于日志排障。
     */
    public record Outcome(Verdict verdict, IntentRecognitionResult result, List<IntentCategory> ambiguousCategories) {

        static Outcome hit(IntentRecognitionResult result) {
            return new Outcome(Verdict.HIT, result, List.of());
        }

        static Outcome ambiguous(List<IntentCategory> categories) {
            return new Outcome(Verdict.AMBIGUOUS, null, categories);
        }

        static Outcome miss() {
            return new Outcome(Verdict.MISS, null, List.of());
        }
    }

    /**
     * 多意图信号较强的并列/顺承连词（多字、歧义小）。
     * 与 {@link IntentRecognitionRouter} 的 L0 结构启发共用同一张表，避免两处维护。
     */
    static final String STRONG_CONJUNCTIONS =
            "然后|接着|顺便|顺带|以及|并且|另外|同时|完了再|之后再|再帮我|再给我|外加";

    /**
     * 子句切分符：标点 + 强连词 + 弱连接词。用于多意图守卫时把复合句拆成独立子句分别匹配。
     * 弱连接词（还要/再查/和/跟等）只用于切分、不作为 L0 信号——单独出现歧义大，
     * 误切分的碎片子句通常匹配不到规则，代价可控（"与"易误伤"参与"等词，不收录）。
     */
    private static final Pattern CLAUSE_SPLITTER = Pattern.compile(
            "[，。；！？!?;,、]|" + STRONG_CONJUNCTIONS + "|还要|还想|再帮|再给|再查|再订|再看|和|跟");

    /**
     * 单条规则：keyword 任一命中且不含 negativeKeyword 即视为命中。
     */
    private static final class Rule {
        final IntentCategory category;
        final Pattern keyword;
        final List<Pattern> negativeKeywords;

        Rule(IntentCategory category, String keyword, List<String> negativeKeywords) {
            this.category = category;
            this.keyword = compile(keyword);
            this.negativeKeywords = negativeKeywords == null
                    ? List.of()
                    : negativeKeywords.stream().map(IntentRuleMatcher::compile).toList();
        }

        boolean matches(String text) {
            if (!keyword.matcher(text).find()) {
                return false;
            }
            for (Pattern negative : negativeKeywords) {
                if (negative.matcher(text).find()) {
                    return false;
                }
            }
            return true;
        }
    }

    private final List<Rule> rules = new ArrayList<>();

    public IntentRuleMatcher() {
        // 优先级从高到低（List 顺序即优先级，命中即短路）。
        // 说明：L1 命中会直接短路并跳过“问题改写”，因此规则需要在“高召回”与“高精度”之间平衡——
        // 关键词尽量覆盖企业差旅真实口语化表达以提升命中率，同时用 negativeKeywords 排除跨类干扰，
        // 并借助规则顺序把“具体动作类（报销/政策/审批/取消/修改）”排在“泛化查询/预订类”之前，避免误路由。

        // 寒暄：整句只由问候/礼貌用语（可多段叠加、可含标点）构成才命中，
        // 既支持“下午好，请问在吗？”这类多段问候，又能避免“你好，帮我订机票”被误判为寒暄。
        String greet = "你好|您好|哈喽|哈啰|嗨|hi|hello|hey|早上好|早安|上午好|中午好|下午好|晚上好"
                       + "|在吗|在不在|在么|在不|有人吗|有人在吗|你在吗|请问|请教一下|打扰一下|打扰了|方便吗";
        String sep = "[\\s,，。.!！?？～~、]*";
        addRule(IntentCategory.GREETING,
                "^(?:" + greet + ")(?:" + sep + "(?:" + greet + "))*" + sep + "$");

        // 报销：动作性极强，优先级高；排除“报销政策/标准/额度/能不能报”等政策类问法，交给政策查询。
        addRule(IntentCategory.REIMBURSEMENT,
                "(报销|报账|报帐|贴票|发票|报销单|费用报销|差旅报销|出差费用|生成报销单|识别发票|发票识别|提交报销|报一下|帮我报|报个销|走报销|电子发票|机票行程单)",
                "政策", "标准", "规定", "制度", "额度", "限额", "能不能报", "能报吗", "报销吗", "可以报", "怎么报", "报销范围", "报销比例");

        // 政策/标准：覆盖企业常用术语（差标、超标、餐标、舱位标准等）。
        addRule(IntentCategory.POLICY_QUERY,
                "(差旅政策|差旅规定|差旅制度|差旅标准|差标|超标|餐标|餐费标准|住宿标准|酒店标准|机票标准|舱位标准|高铁标准|座位标准|费用标准|报销标准|报销政策|报销规定|报销额度|报销范围|能不能报|可以报销吗|能报销吗|预[定订]规定|订票规定|购票规定|签证|入境政策|出差政策|出行政策|差旅管理|出差规定|出差标准)");

        // 审批查询：仅查状态/进度/结果，关键词已足够特异，无需额外排除项。
        addRule(IntentCategory.APPROVAL_QUERY,
                "(审批进度|审批状态|审批结果|审批通过了?吗?|审批到哪|审批到哪个|审批环节|审批意见|审批人|审批流程|我的审批|审批单状态|批了吗|批没批|审没审|通过了没|领导.*批|谁.*审批)");

        // 取消出差/审批。
        addRule(IntentCategory.TRAVEL_CANCEL,
                "(取消出差|取消差旅|取消审批|取消我的(差旅|出差)|撤回(差旅|出差|审批)?申请|撤销(差旅|出差|审批)?申请|撤回审批|这次不去了?|不出差了|出差取消了?|把.*(差旅|出差|申请).*撤了?)");

        // 修改差旅申请；排除“取消/撤回”避免与取消类重叠。
        addRule(IntentCategory.TRAVEL_MODIFY,
                "((修改|变更).*(差旅|出差|申请|行程|订单)|改期|延期|(差旅|出差).*改一?下?|改一下.*(日期|时间|目的地|行程)|调整.*(日期|时间|行程)|把.*(日期|时间|目的地).*改)",
                "取消", "撤回", "撤销", "改签", "退票", "退订");

        // 已有差旅单查询；排除“提交/发起/新建”等创建动作、“做一份/方案”等规划动作与“取消/修改/规划/报销”跨类词。
        addRule(IntentCategory.TRAVEL_ORDER_QUERY,
                "(差旅单|出差单|差旅订单|差旅详情|差旅记录|出差记录|我的差旅|我的出差"
                        + "|(我的|上次|最近|近期|历史|本周|本月|下周|有|查|看)[^，。；！？、]{0,6}(出差|差旅)(安排|行程)"
                        + "|差旅单详情|出差单状态|差旅单状态|上次的?(差旅|出差)|历史(差旅|出差))",
                "提交", "发起", "提个", "新建", "报备", "取消", "规划", "报销", "做一份", "做个", "做一下", "出一份", "方案", "处理", "帮我办", "我要办", "安排一下");
        // 景点/旅游信息。
        addRule(IntentCategory.ATTRACTIONS_QUERY,
                "(有什么好玩|好玩的地方|景点|景区|风景区|名胜|游玩|游览|打卡|必去|必玩|一日游|周边游|当地特色|有什么好吃|美食推荐|特产)");

        // 天气/交通/新闻等通用信息；排除机酒火与预订/报销，避免抢占更具体的意图。
        addRule(IntentCategory.GENERAL_INFO,
                "(天气|气温|多少度|冷不冷|热不热|下雨|下雪|限行|路况|堵不堵|怎么去|怎么走|地铁|公交|打车|时差|汇率|新闻|资讯)",
                "机票", "航班", "火车票", "高铁票", "订", "预[定订]", "报销");

        // 行程规划（必须排在 FLIGHT/TRAIN/HOTEL_SEARCH 之前，优先捕获同时包含“规划+行程/方案”的表达，
        // 避免被机/酒/火查询的关键词抢走）。拆成强弱两条：
        // 1) “安排…行程 / 帮我安排一下”是弱信号——不带“出差/差旅”的纯目的地行程才在 L1 短路成规划；
        //    “安排出差行程 / 安排一下这趟出差”字面像规划、实则多为新建差旅申请（差旅单生命周期归
        //    ItineraryManageAgent，与规划不同组），须放行 L3 结合上下文（是否已有差旅单/审批）裁定。
        // 2) “规划/做…行程 / 行程方案”是强信号——明确要方案即规划，无论是否含“出差/差旅”
        //    （如“出差行程方案”“帮我做一份差旅行程”仍应命中，故不能给强信号也加负向词）。
        addRule(IntentCategory.ITINERARY_PLANNING,
                "(安排.*行程|帮我安排一下)",
                "出差", "差旅");
        addRule(IntentCategory.ITINERARY_PLANNING,
                "(规划.*行程|做.*行程|行程规划|行程安排|行程方案|出行方案|做一份行程|出一份行程|做个行程|帮我规划|规划一下)");

        // 机票查询；排除发票/报销/标准/政策与取消/退票/改签/预订（「预订/预定」同音混写，用预[定订]一并覆盖）
        addRule(IntentCategory.FLIGHT_SEARCH,
                "(查机票|订机票|搜机票|看机票|买机票|机票|航班|飞机票|航班信息|航班时刻|头等舱|经济舱|公务舱|往返机票|单程机票|直飞|廉价航班)",
                "发票", "报销", "标准", "政策", "取消", "退票", "改签", "预[定订]", "订这个", "订下来", "下单");

        // 火车/高铁查询
        addRule(IntentCategory.TRAIN_SEARCH,
                "(查火车|订火车|搜火车|看火车|买火车票|高铁|动车|火车票|火车|车次|列车|高铁票|城际|二等座|一等座|商务座)",
                "标准", "政策", "取消", "退票", "改签", "预[定订]", "订这个", "订下来", "下单");

        // 酒店查询
        addRule(IntentCategory.HOTEL_SEARCH,
                "(查酒店|订酒店|搜酒店|看酒店|住酒店|附近.*酒店|酒店|住宿|住哪里?|住哪儿|入住|宾馆|民宿|快捷酒店|连锁酒店|标间|大床房)",
                "标准", "政策", "报销", "取消", "退订", "预[定订]", "订这个", "订下来", "下单");

        // 预订/改签/退票（通用兜底，放在具体机/酒/火之后，避免抢占具体查询意图）。
        // 「预订/预定」为高频同音混写，统一用预[定订]同时覆盖两种写法。
        addRule(IntentCategory.BOOKING,
                "(预[定订]|下单|订这个|订下来|就订(这个|它)|帮我订(这个|下)|确认(预[定订]|下单)|改签|退票|退订|取消(预[定订]|订单|机票|酒店|火车票))");

        // 新建差旅申请（放在最后，避免抢占查询/规划/修改类）；排除审批状态查询与取消/查询/规划等。
        addRule(IntentCategory.TRAVEL_APPLICATION,
                "(申请出差|出差申请|申请.{0,10}出差|发起(差旅|出差)|提个.*(出差|申请)|提交.*(出差|差旅|申请)|帮我提.*(出差|申请)|我要出差|我想出差|我需要出差|我要去.*出差|(下周|下个月|明天|后天|下下周).*出差|新建(差旅|出差)|报备出差|出差报备)",
                "审批进度", "审批状态", "审批结果", "取消", "查", "规划", "报销");
    }

    private void addRule(IntentCategory category, String keyword) {
        rules.add(new Rule(category, keyword, null));
    }

    /**
     * 注册带排除词的规则：keyword 命中且不含任一 negativeKeyword 时才判定命中。
     * negativeKeyword 同样按正则编译，便于排除跨类干扰、提升 L1 精度。
     */
    private void addRule(IntentCategory category, String keyword, String... negativeKeywords) {
        rules.add(new Rule(category, keyword, List.of(negativeKeywords)));
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * 对输入文本执行 L1 规则匹配（兼容入口：仅暴露单类命中结果）。
     *
     * @param text 改写后的问题文本（已 trim）
     * @return {@link Verdict#HIT} 时返回结果；歧义或未命中均返回 {@link Optional#empty()}
     */
    public Optional<IntentRecognitionResult> match(String text) {
        Outcome outcome = evaluate(text);
        return outcome.verdict() == Verdict.HIT ? Optional.of(outcome.result()) : Optional.empty();
    }

    /**
     * 对输入文本执行 L1 规则匹配，返回完整裁决（供 {@link IntentRecognitionRouter} 区分
     * "未命中（可继续 L2）"与"多意图歧义（应连 L2 一起跳过）"）。
     */
    public Outcome evaluate(String text) {
        // 空输入无从判定，直接放行给 L2/L3
        if (text == null || text.isBlank()) {
            return Outcome.miss();
        }
        String normalized = text.trim();

        // 步骤 1：多意图守卫——按标点/连词拆成子句，逐句匹配
        List<IntentCategory> clauseCategories = matchClauseCategories(normalized);

        // 如果没有子句命中，则返回 MISS
        if (clauseCategories.isEmpty()) {
            return Outcome.miss();
        }
        // 如果有多个子句命中且命中类别横跨 ≥2 个目标子智能体，则返回 AMBIGUOUS
        long distinctAgents = clauseCategories.stream()
                .map(IntentCategory::getDefaultTargetAgent)
                .distinct()
                .count();
        if (distinctAgents >= 2) {
            return Outcome.ambiguous(clauseCategories);
        }

        // 步骤 2：全文按优先级取首个命中规则
        Rule hit = firstMatch(normalized);
        if (hit == null) {
            return Outcome.miss();
        }
        // 如果有规则命中，则返回 HIT
        return Outcome.hit(IntentRecognitionResult.single(
                IntentRecognitionResult.Source.RULE,
                hit.category,
                IntentRecognitionResult.Confidence.HIGH,
                "L1 规则命中：关键词匹配到「" + hit.category.getDescription() + "」",
                null));
    }

    /**
     * 按优先级（List 顺序）返回首个命中的规则，未命中返回 {@code null}。
     * 子句匹配与全文匹配共用同一求值逻辑，两者只是传入的文本范围不同。
     */
    private Rule firstMatch(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (Rule rule : rules) {
            if (rule.matches(text)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 把文本拆成子句后逐句做首中匹配，返回各子句命中的意图类别（去重、保持出现顺序）。
     * 单子句输入（拆不出第二个子句）时，唯一的"子句"就是全文，等价于一次全文关键词匹配。
     *
     * <p>负向词作用于子句而非全句：这是有意为之——复合句里另一半句的词不应干扰本子句的判定
     * （如"查下差旅政策，顺便把发票报销了"中，"政策"不应挡掉后半句的报销意图）。</p>
     *
     * <p>寒暄的特殊处理：寒暄子句不计入类别集合，避免"你好，帮我订机票"这类
     * "问候 + 单任务"被误判为跨智能体多意图；仅当整句只有寒暄命中时才返回
     * {@code [GREETING]}，保证纯寒暄（"下午好，请问在吗？"）仍能走 L1 快路径。</p>
     */
    private List<IntentCategory> matchClauseCategories(String text) {
        String[] clauses = CLAUSE_SPLITTER.split(text);
        Set<IntentCategory> categories = new LinkedHashSet<>();
        boolean hitGreeting = false;
        for (String clause : clauses) {
            Rule hit = firstMatch(clause.trim());
            // 寒暄不参与歧义判定："你好，帮我订机票"仍是单意图
            if (hit != null) {
                if (hit.category == IntentCategory.GREETING) {
                    hitGreeting = true;
                } else {
                    categories.add(hit.category);
                }
            }
        }

        // 如果有寒暄词且无其他意图，则返回寒暄意图
        if (hitGreeting && categories.isEmpty()) {
            return List.of(IntentCategory.GREETING);
        }

        return List.copyOf(categories);
    }

    /**
     * 仅用于单元测试 / 调试：返回当前注册的规则类别。
     */
    Map<IntentCategory, Integer> ruleStats() {
        Map<IntentCategory, Integer> stats = new LinkedHashMap<>();
        for (Rule rule : rules) {
            stats.merge(rule.category, 1, Integer::sum);
        }
        return stats;
    }
}
