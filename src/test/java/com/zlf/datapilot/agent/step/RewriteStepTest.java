package com.zlf.datapilot.agent.step;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.session.SessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RewriteStep 单测（Ticket 10）：多轮补全的边界。
 *
 * <p>这里最要紧的一组断言是「<b>补不出来就拒绝</b>」：带新维度/新时间的追问
 * （「那渠道呢」）如果硬拼成「上一轮问题 + 一句新话」，桩/LLM 很可能只答上一轮，
 * 用户拿到的是看着正常、实际答非所问的数字。这类「静默错」比拒绝严重得多。
 */
class RewriteStepTest {

    private SessionStore sessions;
    private RewriteStep step;

    @BeforeEach
    void setUp() {
        sessions = new SessionStore(5, 100, 30);
        step = new RewriteStep(sessions);
    }

    private AgentContext ctx(String question, String sessionId) {
        AgentContext c = new AgentContext();
        c.setSessionId(sessionId);
        c.setUserQuestion(question);
        return c;
    }

    private void remember(String sessionId, String rewritten, Integer windowDays) {
        sessions.append(sessionId, new SessionStore.Round(
                rewritten, rewritten, List.of("paid_order_cnt"), List.of(), windowDays, "SELECT 1",
                System.currentTimeMillis()));
    }

    @Test
    @DisplayName("无会话：透传（与 Ticket 01 行为一致）")
    void noSessionPassesThrough() {
        AgentContext c = ctx("近7天累计支付订单数是多少", null);
        step.execute(c);

        assertThat(c.getRewrittenQuestion()).isEqualTo("近7天累计支付订单数是多少");
        assertThat(c.getComparisonDays()).isNull();
        assertThat(c.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("有会话但换了个完整问题：不补全，按新问题走")
    void unrelatedQuestionIsNotRewritten() {
        remember("s1", "近7天累计支付订单数是多少", 7);

        AgentContext c = ctx("10月19日当天的总GMV是多少", "s1");
        step.execute(c);

        assertThat(c.getRewrittenQuestion()).isEqualTo("10月19日当天的总GMV是多少");
        assertThat(c.getComparisonDays()).isNull();
        assertThat(c.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("「那环比呢」：补全为「上一轮问题 + 环比：与前 N 天对比」，并标记环比天数")
    void monthOverMonthIsCompleted() {
        remember("s1", "近7天累计支付订单数是多少", 7);

        AgentContext c = ctx("那环比呢", "s1");
        step.execute(c);

        assertThat(c.getErrorMessage()).isNull();
        assertThat(c.getRewrittenQuestion())
                .isEqualTo("近7天累计支付订单数是多少，环比：与前 7 天对比");
        assertThat(c.getComparisonDays()).isEqualTo(7);
    }

    @Test
    @DisplayName("环比天数沿用上一轮窗口：上一轮近 3 天 → 环比比的就是前 3 天")
    void comparisonDaysFollowLastWindow() {
        remember("s1", "近3天累计GMV是多少", 3);
        AgentContext c3 = ctx("环比呢", "s1");
        step.execute(c3);
        assertThat(c3.getComparisonDays()).isEqualTo(3);
        assertThat(c3.getRewrittenQuestion()).contains("与前 3 天对比");
    }

    @Test
    @DisplayName("QA-10 P1-1 回归：单日题（无相对窗口）的环比必须拒绝，不能兜底成 7 天")
    void comparisonOnSingleDayQuestionIsRejected() {
        // 上一轮是单日题：windowDays=null（日期在 filters 里，没有相对窗口）
        sessions.append("s2", new SessionStore.Round("10月19日当天的总GMV是多少", "10月19日当天的总GMV是多少",
                List.of("gmv_total"), List.of(), null, "SELECT 1", System.currentTimeMillis()));

        AgentContext c = ctx("那环比呢", "s2");
        step.execute(c);

        // 关键：不设环比天数 —— 以前这里会凭空兜底成 7，而 copyWithOffset 挪不动 filters 里的日期，
        // 于是「前 7 天」查的还是 10-19，把本期数字当成上期说出来（静默错数）
        assertThat(c.getComparisonDays()).isNull();
        assertThat(c.getRewrittenQuestion()).isNull();
        assertThat(c.getErrorMessage()).contains("按天查询").contains("近 N 天");
        assertThat(c.getRejectedBy()).isEqualTo("rewrite");
    }

    @Test
    @DisplayName("明确拒绝都带 rejectedBy=rewrite：落库才会是 REJECTED 而不是 FAILED")
    void rejectionsAreTaggedAsRewrite() {
        remember("s1", "近7天累计支付订单数是多少", 7);

        AgentContext yoy = ctx("那同比呢", "s1");
        step.execute(yoy);
        assertThat(yoy.getRejectedBy()).isEqualTo("rewrite");

        AgentContext dim = ctx("那渠道呢", "s1");
        step.execute(dim);
        assertThat(dim.getRejectedBy()).isEqualTo("rewrite");
    }

    @Test
    @DisplayName("纯指代（「再算一遍」）：去掉指代词后没有新语义，直接沿用上一轮问题")
    void pureAnaphoraReusesLastQuestion() {
        remember("s1", "近7天累计支付订单数是多少", 7);

        AgentContext c = ctx("再算一遍", "s1");
        step.execute(c);

        assertThat(c.getErrorMessage()).isNull();
        assertThat(c.getRewrittenQuestion()).isEqualTo("近7天累计支付订单数是多少");
        assertThat(c.getComparisonDays()).isNull();
    }

    @Test
    @DisplayName("带新维度的追问（「那渠道呢」）：显式拒绝，并给出可执行的完整问法")
    void followUpWithNewDimensionIsRejected() {
        remember("s1", "近7天累计支付订单数是多少", 7);

        AgentContext c = ctx("那渠道呢", "s1");
        step.execute(c);

        assertThat(c.getErrorMessage())
                .contains("多轮追问暂只支持环比")
                .contains("那渠道呢");
        // 拒绝时不能悄悄留一个补全结果，否则上层若忽略 errorMessage 就会拿它去查
        assertThat(c.getRewrittenQuestion()).isNull();
    }

    @Test
    @DisplayName("同比：明确说「没有去年同期」，不编一个同比数，也不编一个「最近 N 天」的假前提")
    void yearOverYearIsRejectedWithReason() {
        remember("s1", "近7天累计支付订单数是多少", 7);

        AgentContext c = ctx("那同比呢", "s1");
        step.execute(c);

        assertThat(c.getErrorMessage()).contains("暂不支持同比").contains("去年同期");
        // QA-10 P2-2：文案里不再出现兜底出来的「最近 7 天」
        assertThat(c.getErrorMessage()).doesNotContain("最近 7 天");
        assertThat(c.getComparisonDays()).isNull();
    }

    // ---------------- 口径边界守卫（Ticket 17 / C5②） ----------------
    // 这一组测的不是「拦得住」，而是「拦得准」：uv 指标泛化到别名、按天问法必须放行、
    // 「总」不能当跨期词。QA-16 P2-1 指出的正是「文档承诺显式拒绝、代码只回无法识别」。

    @Test
    @DisplayName("C5②：近 7 天累计支付用户数 → 显式拒绝，并说明口径原因（不是「换个问法」）")
    void crossPeriodUvIsRejectedWithMetricReason() {
        AgentContext c = ctx("近7天累计支付用户数是多少", null);
        step.execute(c);

        // 不给 LLM 自由发挥的机会，也不能落到「无法识别查询意图」那种不解释的兜底
        assertThat(c.getErrorMessage())
                .contains("当天去重人数")
                .contains("COUNT(DISTINCT user_id)")
                .contains("不会给一个偏大的数")
                .doesNotContain("无法识别");
        // 文案要能照着改问：既给按天，也给可加的替代指标
        assertThat(c.getErrorMessage()).contains("每天的支付用户数").contains("支付订单数");
        assertThat(c.getRewrittenQuestion()).isNull();
        assertThat(c.getRejectedBy()).isEqualTo("rewrite");
    }

    @Test
    @DisplayName("C5②：别名（成交用户数/付费用户数）同样拦 —— 词表对齐 metric_def 的 aliases")
    void aliasesAreCovered() {
        for (String q : List.of("近7天合计成交用户数是多少", "本月付费用户数是多少", "最近30天付费人数")) {
            AgentContext c = ctx(q, null);
            step.execute(c);
            assertThat(c.getErrorMessage()).as(q).contains("当天去重人数");
        }
    }

    @Test
    @DisplayName("单日问法放行：10月19日的支付用户数（当天去重，没有跨期相加）")
    void singleDayUvPassesThrough() {
        AgentContext c = ctx("10月19日的支付用户数是多少", null);
        step.execute(c);

        assertThat(c.getErrorMessage()).isNull();
        assertThat(c.getRewrittenQuestion()).isEqualTo("10月19日的支付用户数是多少");
    }

    @Test
    @DisplayName("按天问法放行：近 7 天每天的支付用户数（每天各自去重，合法）")
    void perDayUvPassesThrough() {
        for (String q : List.of("近7天每天的支付用户数", "近7天每日支付用户数走势", "近7天支付用户数的按天趋势")) {
            AgentContext c = ctx(q, null);
            step.execute(c);
            assertThat(c.getErrorMessage()).as(q).isNull();
        }
    }

    @Test
    @DisplayName("可加指标不受影响：近 7 天累计支付订单数 / 总 GMV 照常走")
    void additiveMetricsAreUntouched() {
        for (String q : List.of("近7天累计支付订单数是多少", "10月19日当天的总GMV是多少")) {
            AgentContext c = ctx(q, null);
            step.execute(c);
            assertThat(c.getErrorMessage()).as(q).isNull();
        }
    }

    @Test
    @DisplayName("「总」不当跨期词：支付用户数占总订单数的比例 不该被拦（占比不是求和）")
    void totalInRatioIsNotCrossPeriod() {
        AgentContext c = ctx("支付用户数占总订单数的比例是多少", null);
        step.execute(c);

        assertThat(c.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("有会话时同样先拦口径：守卫在改写之前，不依赖上一轮")
    void scopeGuardRunsBeforeRewrite() {
        remember("s1", "近7天累计支付订单数是多少", 7);

        AgentContext c = ctx("近7天累计支付用户数是多少", "s1");
        step.execute(c);

        assertThat(c.getErrorMessage()).contains("当天去重人数");
        assertThat(c.getComparisonDays()).isNull();
    }

    @Test
    @DisplayName("Ticket 22：「那么我需要完整的报告」= 报告请求，沿用上一轮，不拒绝")
    void reportRequestReusesLastQuestion() {
        remember("s1", "10月19日各渠道的GMV是多少", null);

        AgentContext c = ctx("那么我需要完整的报告", "s1");
        step.execute(c);

        // 这句话本身不含取数语义，此前被判成「换了维度的追问」直接拒绝，
        // 提示「请把条件说全」—— 条件本来就在上一轮里，属于答非所问
        assertThat(c.getErrorMessage()).isNull();
        assertThat(c.getRewrittenQuestion()).isEqualTo("10月19日各渠道的GMV是多少");
        assertThat(c.isReportRequested()).isTrue();
    }

    @Test
    @DisplayName("带完整条件的「报告」请求：不能按报告处理，否则会忽略它自己那句的条件")
    void reportWithOwnConditionsIsNotTreatedAsFollowUp() {
        remember("s1", "10月19日各渠道的GMV是多少", null);

        AgentContext c = ctx("近7天各省份GMV的报告", "s1");
        step.execute(c);

        assertThat(c.getErrorMessage()).isNull();
        assertThat(c.getRewrittenQuestion()).isEqualTo("近7天各省份GMV的报告");
        assertThat(c.isReportRequested()).isFalse();
    }

    @Test
    @DisplayName("没有上一轮却要报告：拒绝并给出下一步，而不是把这句话丢给模型瞎猜")
    void reportRequestWithoutHistoryIsRejected() {
        AgentContext c = ctx("我需要完整的报告", null);
        step.execute(c);

        assertThat(c.getErrorMessage()).contains("先问一个具体问题");
        assertThat(c.getRejectedBy()).isEqualTo("rewrite");
        assertThat(c.isReportRequested()).isFalse();
    }
}
