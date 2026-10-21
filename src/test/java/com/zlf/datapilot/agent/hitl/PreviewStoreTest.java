package com.zlf.datapilot.agent.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.ir.DimensionRef;
import com.zlf.datapilot.ir.Filter;
import com.zlf.datapilot.ir.FilterOp;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.ir.SelectMode;
import com.zlf.datapilot.sql.SqlCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预览快照存储（Ticket 12）。三条不变量：存进去的 IR 改不动、过期真会失效、超上限真会淘汰。
 */
class PreviewStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static AgentContext ctxOf(String question) {
        AgentContext ctx = new AgentContext();
        ctx.setTraceId("trace-1");
        ctx.setSessionId("s-1");
        ctx.setUserQuestion(question);
        QueryIr ir = new QueryIr();
        ir.setSelectMode(SelectMode.AGG);
        ir.setMetrics(new ArrayList<>(List.of(metric("channel_conv_rate"))));
        ir.setDimensions(new ArrayList<>(List.of(dim("channel_name"))));
        Filter f = new Filter();
        f.setField("channel_name");
        f.setOp(FilterOp.EQ);
        f.setValue("APP");
        ir.setFilters(new ArrayList<>(List.of(f)));
        ctx.setIr(ir);
        ctx.setSqlCommand(SqlCommand.of("SELECT 1 FROM t WHERE dt > ? AND dt <= ?", List.of("2026-10-12", "2026-10-19")));
        ctx.setGeneratedSql(ctx.getSqlCommand().sql());
        ctx.addGateResult("存在性=PASS");
        ctx.addGateResult("权限=PASS");
        ctx.addGateResult("代价=PASS");
        ctx.addGateResult("语法=PASS");
        return ctx;
    }

    private static MetricRef metric(String id) {
        MetricRef m = new MetricRef();
        m.setMetricId(id);
        return m;
    }

    private static DimensionRef dim(String field) {
        DimensionRef d = new DimensionRef();
        d.setField(field);
        return d;
    }

    @Test
    @DisplayName("存下来的快照带 previewId、原始问题与四道闸门结果")
    void saveKeepsWhatPreviewProved() {
        PreviewStore store = new PreviewStore(mapper, 10, 60);

        PreviewStore.Preview p = store.save(ctxOf("各渠道转化率"));

        assertThat(p.previewId()).hasSize(32);
        assertThat(p.traceId()).isEqualTo("trace-1");
        assertThat(p.question()).isEqualTo("各渠道转化率");
        assertThat(p.gates()).hasSize(4);
        assertThat(p.params()).containsExactly("2026-10-12", "2026-10-19");
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("环比天数跟着快照走：它不在 IR 里，丢了会让执行只出一半")
    void comparisonDaysTravelWithTheSnapshot() {
        PreviewStore store = new PreviewStore(mapper, 10, 60);
        AgentContext ctx = ctxOf("那环比呢");
        ctx.setComparisonDays(7);

        assertThat(store.save(ctx).comparisonDays()).isEqualTo(7);
        assertThat(store.save(ctxOf("普通问题")).comparisonDays()).isNull();
    }

    @Test
    @DisplayName("解析出的 IR 是全新对象：改它改不到快照（否则 execute 能篡改预览基准）")
    void parsedIrIsDetachedFromSnapshot() {
        PreviewStore store = new PreviewStore(mapper, 10, 60);
        PreviewStore.Preview p = store.save(ctxOf("各渠道转化率"));

        QueryIr first = store.parseIr(p);
        first.getFilters().get(0).setValue("HACKED");
        QueryIr second = store.parseIr(p);

        assertThat(second.getFilters().get(0).getValue()).isEqualTo("APP");
    }

    @Test
    @DisplayName("过期就取不到：TTL 到了返回 null，不返回一个「看起来还能用」的旧快照")
    void expiredPreviewIsGone() throws InterruptedException {
        PreviewStore store = new PreviewStore(mapper, 10, 1);
        PreviewStore.Preview p = store.save(ctxOf("各渠道转化率"));

        assertThat(store.get(p.previewId())).isNotNull();
        Thread.sleep(1200);
        assertThat(store.get(p.previewId())).isNull();
        assertThat(store.count()).isZero();
    }

    @Test
    @DisplayName("超上限淘汰最久未创建的，不会无限涨")
    void oldestIsEvictedAtCapacity() {
        PreviewStore store = new PreviewStore(mapper, 2, 60);
        PreviewStore.Preview first = store.save(ctxOf("q1"));
        PreviewStore.Preview second = store.save(ctxOf("q2"));
        PreviewStore.Preview third = store.save(ctxOf("q3"));

        assertThat(store.count()).isEqualTo(2);
        assertThat(store.get(first.previewId())).isNull();
        assertThat(store.get(second.previewId())).isNotNull();
        assertThat(store.get(third.previewId())).isNotNull();
    }

    @Test
    @DisplayName("空/不存在的 previewId 返回 null，不抛异常、不猜一份")
    void unknownIdReturnsNull() {
        PreviewStore store = new PreviewStore(mapper, 10, 60);

        assertThat(store.get(null)).isNull();
        assertThat(store.get("  ")).isNull();
        assertThat(store.get("nope")).isNull();
    }
}
