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
    @DisplayName("环比天数沿用上一轮窗口；上一轮没有窗口时按 7 天兜底")
    void comparisonDaysFollowLastWindow() {
        remember("s1", "近3天累计GMV是多少", 3);
        AgentContext c3 = ctx("环比呢", "s1");
        step.execute(c3);
        assertThat(c3.getComparisonDays()).isEqualTo(3);
        assertThat(c3.getRewrittenQuestion()).contains("与前 3 天对比");

        SessionStore other = new SessionStore(5, 100, 30);
        other.append("s2", new SessionStore.Round("10月19日当天的总GMV是多少", "10月19日当天的总GMV是多少",
                List.of("gmv_total"), List.of(), null, "SELECT 1", System.currentTimeMillis()));
        AgentContext c2 = ctx("那环比呢", "s2");
        new RewriteStep(other).execute(c2);
        assertThat(c2.getComparisonDays()).isEqualTo(7);
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
    @DisplayName("同比：数据只有 7 天，明确说「没有去年同期」，不编一个同比数")
    void yearOverYearIsRejectedWithReason() {
        remember("s1", "近7天累计支付订单数是多少", 7);

        AgentContext c = ctx("那同比呢", "s1");
        step.execute(c);

        assertThat(c.getErrorMessage()).contains("暂不支持同比").contains("去年同期");
        assertThat(c.getComparisonDays()).isNull();
    }
}
