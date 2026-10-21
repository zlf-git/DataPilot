package com.zlf.datapilot.agent.gate;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.ir.DimensionRef;
import com.zlf.datapilot.ir.Filter;
import com.zlf.datapilot.ir.FilterOp;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.ir.SelectMode;
import com.zlf.datapilot.knowledge.OntologyCache;
import com.zlf.datapilot.sql.SqlCommand;
import com.zlf.datapilot.sql.SqlValidator;
import com.zlf.datapilot.support.TestOntology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 五道闸门 + 闸门链单测（Ticket 06c 任务 2）。
 *
 * <p>四道拦截型闸门各 1 个 reject 用例。闸门⑤（结果自检）<b>按设计不拦截</b>（契约见
 * {@link GateResult} 注释：空结果不是错误，只附 warning），所以它的用例断言的是
 * 「不 reject + warning 能穿过 GateChain 到调用方」—— 这正是 QA-05 P2-1 那个 bug 的回归点：
 * 此前 runPhase 一律 return ok()，闸门⑤的「无数据」提示永远到不了 AgentEngine。
 */
class GateChainTest {

    private OntologyCache cache;
    private GateChain chain;

    @BeforeEach
    void setUp() {
        cache = TestOntology.cache();
        chain = new GateChain(List.of(
                new ExistenceGate(cache),
                new AuthorityGate(cache),
                new CostGate(cache),
                new SyntaxGate(new SqlValidator(cache)),
                new ResultCheckGate(cache)));
    }

    // ---------------- 闸门①~④：各 1 个 reject ----------------

    @Test
    @DisplayName("闸门① 存在性：指标不在白名单 → REJECT")
    void existenceRejectsUnknownMetric() {
        AgentContext ctx = ctx(ir("gmv_by_imagination"));

        GateResult r = new ExistenceGate(cache).check(ctx);

        assertThat(r.isPass()).isFalse();
        assertThat(r.getReason()).contains("指标不存在").contains("gmv_by_imagination");
    }

    @Test
    @DisplayName("闸门② 权限：指标关联表不在只读白名单 → REJECT")
    void authorityRejectsTableOutsideWhitelist() {
        // 模拟元数据里出现一张白名单外的表（例如别人往 metric_def 塞了订单明细表）
        when(cache.metricTables()).thenReturn(Map.of("gmv_total", "ods_order_detail"));

        GateResult r = new AuthorityGate(cache).check(ctx(ir("gmv_total")));

        assertThat(r.isPass()).isFalse();
        assertThat(r.getReason()).contains("越权访问表").contains("ods_order_detail");
    }

    @Test
    @DisplayName("闸门③ 代价：指标数超上限 → REJECT（阈值读 gate_config，本替身为 1）")
    void costRejectsTooManyMetrics() {
        QueryIr ir = ir("gmv_total");
        MetricRef second = new MetricRef();
        second.setMetricId("gmv_channel");
        ir.setMetrics(new ArrayList<>(List.of(ir.getMetrics().get(0), second)));

        GateResult r = new CostGate(cache).check(ctx(ir));

        assertThat(r.isPass()).isFalse();
        assertThat(r.getReason()).contains("查询过于复杂").contains("指标数 2 > 1");
    }

    @Test
    @DisplayName("闸门④ 语法：SQL 引用白名单外的表 → REJECT（JSqlParser 真实解析）")
    void syntaxRejectsTableOutsideWhitelist() {
        AgentContext ctx = ctx(ir("gmv_total"));
        ctx.setSqlCommand(SqlCommand.of("SELECT gmv FROM ods_order_raw WHERE dt = ?", List.of("2026-10-19")));

        GateResult r = new SyntaxGate(new SqlValidator(cache)).check(ctx);

        assertThat(r.isPass()).isFalse();
        assertThat(r.getReason()).contains("白名单外的表").contains("ods_order_raw");
    }

    @Test
    @DisplayName("闸门④ 语法：SQL 本身不合法 → REJECT（报语法错误，不是白名单错误）")
    void syntaxRejectsMalformedSql() {
        AgentContext ctx = ctx(ir("gmv_total"));
        ctx.setSqlCommand(SqlCommand.of("SELECT FROM WHERE", List.of()));

        GateResult r = new SyntaxGate(new SqlValidator(cache)).check(ctx);

        assertThat(r.isPass()).isFalse();
        assertThat(r.getReason()).contains("SQL 语法错误");
    }

    @Test
    @DisplayName("闸门③ 代价：跨表占比的分母表计入 join 数（QA-05 P2-4 回归）")
    void costCountsDenominatorTableAsJoin() {
        AgentContext ctx = ctx(ir("gmv_channel_ratio"));

        // 主表(渠道) + 分母表(总览) = 2 张表 → join 数 1，恰好等于 max_join=1，应通过
        assertThat(new CostGate(cache).check(ctx).isPass()).isTrue();

        // 把 max_join 收紧到 0：单表题还能过，跨表占比必须被拒 —— 这正是不数分母表时漏掉的那一刀
        OntologyCache strict = TestOntology.cache();
        when(strict.gateThreshold(anyString(), anyInt()))
                .thenAnswer(inv -> "max_join".equals(inv.getArgument(0)) ? 0 : inv.getArgument(1));
        GateResult r = new CostGate(strict).check(ctx);

        assertThat(r.isPass()).isFalse();
        assertThat(r.getReason()).contains("join 表数 1 > 0");
        assertThat(new CostGate(strict).check(ctx(ir("gmv_channel"))).isPass()).isTrue();
    }

    // ---------------- 闸门⑤：不拦截，但 warning 必须传出去 ----------------

    @Test
    @DisplayName("闸门⑤ 结果自检：空结果不 reject，只带 warning（设计如此）")
    void resultCheckWarnsButDoesNotReject() {
        AgentContext ctx = ctx(ir("gmv_total"));
        ctx.setQueryResult(new ArrayList<>());

        GateResult r = new ResultCheckGate(cache).check(ctx);

        assertThat(r.isPass()).isTrue();
        assertThat(r.getWarning()).contains("无数据");
    }

    @Test
    @DisplayName("闸门⑤ 结果自检：单行全 NULL 也算无数据（SUM 空集形态）")
    void resultCheckTreatsAllNullSingleRowAsEmpty() {
        AgentContext ctx = ctx(ir("gmv_total"));
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("gmv_total", null);
        ctx.setQueryResult(new ArrayList<>(List.of(row)));

        GateResult r = new ResultCheckGate(cache).check(ctx);

        assertThat(r.isPass()).isTrue();
        assertThat(r.getWarning()).contains("聚合返回空值");
    }

    @Test
    @DisplayName("GateChain：RESULT 阶段把闸门⑤的 warning 传给调用方（QA-05 P2-1 回归）")
    void chainPropagatesWarningFromResultPhase() {
        AgentContext ctx = ctx(ir("gmv_total"));
        ctx.setQueryResult(new ArrayList<>());

        GateResult r = chain.runPhase(Gate.Phase.RESULT, ctx);

        assertThat(r.isPass()).isTrue();
        assertThat(r.getWarning()).contains("无数据");
        assertThat(ctx.getGateResults()).containsExactly("结果自检=PASS(WARN)");
    }

    @Test
    @DisplayName("GateChain：任一闸门 reject 即停，后续闸门不执行（fail-fast）")
    void chainStopsAtFirstReject() {
        AgentContext ctx = ctx(ir("gmv_by_imagination"));

        GateResult r = chain.runPhase(Gate.Phase.IR, ctx);

        assertThat(r.isPass()).isFalse();
        assertThat(r.getReason()).contains("指标不存在");
        // 只留存在性一条，权限/代价没有跑到
        assertThat(ctx.getGateResults()).containsExactly("存在性=REJECT");
    }

    @Test
    @DisplayName("GateChain：全部通过且三道闸门都带 warning 时合并返回")
    void chainMergesMultipleWarnings() {
        Gate warn1 = stubGate("甲", Gate.Phase.IR, GateResult.okWarning("告警一"));
        Gate warn2 = stubGate("乙", Gate.Phase.IR, GateResult.okWarning("告警二"));
        GateChain c = new GateChain(List.of(warn1, warn2));
        AgentContext ctx = ctx(ir("gmv_total"));

        GateResult r = c.runPhase(Gate.Phase.IR, ctx);

        assertThat(r.isPass()).isTrue();
        assertThat(r.getWarning()).isEqualTo("告警一；告警二");
        assertThat(ctx.getGateResults()).containsExactly("甲=PASS(WARN)", "乙=PASS(WARN)");
    }

    // ---------------- 夹具 ----------------

    private static Gate stubGate(String name, Gate.Phase phase, GateResult result) {
        return new Gate() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Phase phase() {
                return phase;
            }

            @Override
            public GateResult check(AgentContext ctx) {
                return result;
            }
        };
    }

    private static AgentContext ctx(QueryIr ir) {
        AgentContext ctx = new AgentContext();
        ctx.setIr(ir);
        ctx.setGeneratedSql("SELECT 1");
        ctx.setSqlCommand(SqlCommand.of("SELECT total_gmv FROM ads_trade_overview_di WHERE dt = ?",
                List.of("2026-10-19")));
        return ctx;
    }

    private static QueryIr ir(String metricId) {
        QueryIr ir = new QueryIr();
        ir.setSelectMode(SelectMode.AGG);
        MetricRef m = new MetricRef();
        m.setMetricId(metricId);
        ir.setMetrics(new ArrayList<>(List.of(m)));
        ir.setDimensions(new ArrayList<>());
        ir.setOrderBy(new ArrayList<>());
        Filter f = new Filter();
        f.setField("dt");
        f.setOp(FilterOp.EQ);
        f.setValue("2026-10-19");
        ir.setFilters(new ArrayList<>(List.of(f)));
        return ir;
    }

    /** 预留：某些用例需要维度但本例未用，保留签名便于后续扩展 */
    @SuppressWarnings("unused")
    private static DimensionRef dim(String field) {
        DimensionRef d = new DimensionRef();
        d.setField(field);
        return d;
    }
}
