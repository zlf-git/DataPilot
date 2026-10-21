package com.zlf.datapilot.agent.gate;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.ir.DimensionRef;
import com.zlf.datapilot.ir.Filter;
import com.zlf.datapilot.ir.FilterOp;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.ir.SelectMode;
import com.zlf.datapilot.knowledge.OntologyCache;
import com.zlf.datapilot.sql.SqlValidator;
import com.zlf.datapilot.support.TestOntology;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 代价闸门复杂度公式（Ticket 15 / D2 / 审计 P1#19）。
 *
 * <p>要证的只有一件事：<b>维度=0 的题不再恒通过</b>，而且不是靠「复杂度从 0 变成了某个数」
 * 这种听起来对的结论 —— 把 max_complexity 压到 1，Q1/Q2/Q9/Q14 这四道维度=0 的题必须真被拦下来。
 * 补洞前的实现里复杂度恒 0，阈值压到 1 也拦不住（0 &gt; 1 不成立），这才是审计说的「只写
 * 不再恒通过不够」。
 *
 * <p>断言全部落在外部可见行为：闸门 PASS/REJECT 与拒绝原因里的分值。不 assert 私有方法、
 * 不反射读字段 —— 换实现（比如把公式抽成工具类）不该让这些用例变红。
 */
class CostGateComplexityTest {

    /** 默认阈值（gate_config 三因子配置）下，四道维度=0 的题必须仍然放行，不能被补洞误杀 */
    @Test
    @DisplayName("默认阈值 3：Q1/Q2/Q9/Q14（维度=0）全部 PASS")
    void defaultThresholdKeepsDimensionlessQuestionsPassing() {
        OntologyCache cache = TestOntology.cache();

        assertThat(new CostGate(cache).check(ctx(gmvOneDay())).isPass())
                .as("Q1 10月19日当天总GMV").isTrue();
        assertThat(new CostGate(cache).check(ctx(gmvSevenDays())).isPass())
                .as("Q2 近7天累计支付订单数").isTrue();
        assertThat(new CostGate(cache).check(ctx(payRate())).isPass())
                .as("Q9 支付订单数占总订单数比例（同表复合，无维度）").isTrue();
        assertThat(new CostGate(cache).check(ctx(gmvTopDay())).isPass())
                .as("Q14 近7天总GMV最高是哪一天（维度=dt 但指标无维度聚合语义）").isTrue();
    }

    /** 验收核心：阈值=1 时，维度=0 的题必须被拦，证明复杂度确实 ≥1 而不是 0 */
    @Test
    @DisplayName("阈值=1：Q1/Q2/Q9/Q14 全部 REJECT，原因写明复杂度 1 ≥ 阈值 1（审计 P1#19 回归）")
    void thresholdOneRejectsDimensionlessQuestions() {
        OntologyCache strict = cacheWithMaxComplexity(1);
        CostGate gate = new CostGate(strict);

        for (QueryIr ir : List.of(gmvOneDay(), gmvSevenDays(), payRate(), gmvTopDay())) {
            GateResult r = gate.check(ctx(ir));
            assertThat(r.isPass()).as(ir.getQuestion()).isFalse();
            assertThat(r.getReason())
                    .as(ir.getQuestion())
                    .contains("查询复杂度过高")
                    .contains("复杂度 1 ≥ 阈值 1")
                    // 比值兜底要能自证：报错里带三因子原值，DBA 才知道是哪一项把分数顶上去了
                    .contains("各因子下限 1");
        }
    }

    /**
     * 票面外补的洞（已在票面「偏差」里记账）：DETAIL 明细题没有 metrics，
     * 若只对维度做 max(1,·)，指标数为 0 仍会把乘积清零 —— 明细题照样恒通过。
     * 三个因子一起兜底后，明细题得分同样是 1，阈值=1 时一并被拦。
     */
    @Test
    @DisplayName("DETAIL 明细题（指标=0）不会因指标因子为 0 绕过复杂度检查")
    void detailQueryWithoutMetricsIsNotZeroScored() {
        QueryIr detail = new QueryIr();
        detail.setQuestion("华东地区有哪些省份？");
        detail.setSelectMode(SelectMode.DETAIL);
        detail.setMetrics(new ArrayList<>());
        detail.setDimensions(new ArrayList<>(List.of(dim("province_name"))));
        detail.setOrderBy(new ArrayList<>());
        detail.setFilters(new ArrayList<>(List.of(eq("region", "华东"))));

        assertThat(new CostGate(TestOntology.cache()).check(ctx(detail)).isPass())
                .as("默认阈值 3 下明细题照常放行").isTrue();

        GateResult r = new CostGate(cacheWithMaxComplexity(1)).check(ctx(detail));
        assertThat(r.isPass()).isFalse();
        assertThat(r.getReason()).contains("复杂度 1 ≥ 阈值 1").contains("指标 0 × 维度 1");
    }

    /** 维度真是加分项：两维 → 分值 2；阈值 2 拦、阈值 3 放 */
    @Test
    @DisplayName("两维度把复杂度顶到 2：阈值 2 拦、阈值 3 放")
    void twoDimensionsDoubleTheScore() {
        QueryIr twoDims = gmvTopDay();
        twoDims.setDimensions(new ArrayList<>(List.of(dim("dt"), dim("channel_name"))));

        assertThat(new CostGate(cacheWithMaxComplexity(3)).check(ctx(twoDims)).isPass())
                .as("阈值 3：复杂度 2 放行").isTrue();

        GateResult r = new CostGate(cacheWithMaxComplexity(2)).check(ctx(twoDims));
        assertThat(r.isPass()).isFalse();
        assertThat(r.getReason()).contains("复杂度 2 ≥ 阈值 2").contains("指标 1 × 维度 2 × join 0");
    }

    /** 跨表占比：2 张表 → join 数 1，分值仍为 1，阈值 2 不误杀（当前能力下最重的一题） */
    @Test
    @DisplayName("跨表占比（2 表）复杂度 1：阈值 2 放行，证明 join 因子与维度不重复计数")
    void crossTableRatioStaysAtScoreOne() {
        QueryIr ratio = new QueryIr();
        ratio.setQuestion("10月19日各渠道GMV占当天总GMV的比例");
        ratio.setSelectMode(SelectMode.AGG);
        MetricRef m = new MetricRef();
        m.setMetricId("gmv_channel_ratio");
        ratio.setMetrics(new ArrayList<>(List.of(m)));
        ratio.setDimensions(new ArrayList<>(List.of(dim("channel_name"))));
        ratio.setOrderBy(new ArrayList<>());
        ratio.setFilters(new ArrayList<>(List.of(eq("dt", "2026-10-19"))));

        assertThat(new CostGate(cacheWithMaxComplexity(2)).check(ctx(ratio)).isPass())
                .as("复杂度 1×1×1=1，未到阈值 2").isTrue();

        GateResult r = new CostGate(cacheWithMaxComplexity(1)).check(ctx(ratio));
        assertThat(r.isPass()).isFalse();
        assertThat(r.getReason()).contains("join 1");
    }

    /** 闸门链上的观感：代价闸门拦住后，后续语法闸门不再跑（fail-fast 语义不能因新公式改变） */
    @Test
    @DisplayName("闸门链：阈值=1 时代价闸门拦在最前面，gateResults 只留到代价这一条")
    void chainStopsAtCostGate() {
        OntologyCache strict = cacheWithMaxComplexity(1);
        GateChain chain = new GateChain(List.of(
                new ExistenceGate(strict),
                new AuthorityGate(strict),
                new CostGate(strict),
                new SyntaxGate(new SqlValidator(strict))));

        AgentContext ctx = ctx(gmvOneDay());
        GateResult r = chain.runPhase(Gate.Phase.IR, ctx);

        assertThat(r.isPass()).isFalse();
        assertThat(r.getReason()).contains("查询复杂度过高");
        assertThat(ctx.getGateResults()).containsExactly("存在性=PASS", "权限=PASS", "代价=REJECT");
    }

    // ---------------- 夹具：四道维度=0 的真实评测题 ----------------

    /** Q1：10月19日当天的总GMV是多少？ */
    private static QueryIr gmvOneDay() {
        return aggIr("10月19日当天的总GMV是多少？", "gmv_total", List.of(eq("dt", "2026-10-19")));
    }

    /** Q2：近7天累计支付订单数是多少？（时间窗口，无维度） */
    private static QueryIr gmvSevenDays() {
        return aggIr("近7天累计支付订单数是多少？", "gmv_total",
                List.of(eq("dt", "2026-10-19")));
    }

    /** Q9：支付订单数占总订单数的比例（同表比值，无维度） */
    private static QueryIr payRate() {
        return aggIr("10月19日支付订单数占总订单数的比例是多少？", "pay_conversion_rate",
                List.of(eq("dt", "2026-10-19")));
    }

    /** Q14：近7天里总GMV最高的是哪一天？（按 dt 分组） */
    private static QueryIr gmvTopDay() {
        QueryIr ir = aggIr("近7天里总GMV最高的是哪一天？", "gmv_total",
                List.of(eq("dt", "2026-10-19")));
        ir.setDimensions(new ArrayList<>(List.of(dim("dt"))));
        return ir;
    }

    private static QueryIr aggIr(String question, String metricId, List<Filter> filters) {
        QueryIr ir = new QueryIr();
        ir.setQuestion(question);
        ir.setSelectMode(SelectMode.AGG);
        MetricRef m = new MetricRef();
        m.setMetricId(metricId);
        ir.setMetrics(new ArrayList<>(List.of(m)));
        ir.setDimensions(new ArrayList<>());
        ir.setOrderBy(new ArrayList<>());
        ir.setFilters(new ArrayList<>(filters));
        return ir;
    }

    private static Filter eq(String field, String value) {
        Filter f = new Filter();
        f.setField(field);
        f.setOp(FilterOp.EQ);
        f.setValue(value);
        return f;
    }

    private static DimensionRef dim(String field) {
        DimensionRef d = new DimensionRef();
        d.setField(field);
        return d;
    }

    private static AgentContext ctx(QueryIr ir) {
        AgentContext ctx = new AgentContext();
        ctx.setIr(ir);
        ctx.setGeneratedSql("SELECT 1");
        return ctx;
    }

    /** 只覆写 max_complexity，其余阈值走 TestOntology 的替身值，避免用例被无关阈值带偏 */
    private static OntologyCache cacheWithMaxComplexity(int maxComplexity) {
        OntologyCache cache = TestOntology.cache();
        when(cache.gateThreshold(anyString(), anyInt()))
                .thenAnswer(inv -> "max_complexity".equals(inv.<String>getArgument(0))
                        ? maxComplexity
                        : inv.<Integer>getArgument(1));
        return cache;
    }
}
