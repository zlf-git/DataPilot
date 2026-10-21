package com.zlf.datapilot.agent.gate;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.support.TestOntology;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸门⑤ 结果自检的异常值判定（Ticket 16 / G1）。
 *
 * <p>G1 的拍板是「阈值写死常量，不进 gate_config」：行数上限是运维口径可以配，
 * 而「什么叫离谱」是判断标准 —— 放进配置表就等于允许一条 UPDATE 把告警关掉。
 * 这些用例锁的就是这个判断标准本身：负值超容差要告警、容差内不告警、空值比例超 20% 要告警。
 *
 * <p>全部只断言外部行为（warning 文案 / 是否 PASS），不碰私有方法。
 */
class ResultCheckGateTest {

    private final ResultCheckGate gate = new ResultCheckGate(TestOntology.cache());

    @Test
    @DisplayName("负值超容差：告警且点名列与值（不拦截）")
    void warnsOnNegativeValue() {
        AgentContext ctx = ctxOf(row("gmv", new BigDecimal("-12.50")));

        GateResult r = gate.check(ctx);

        assertThat(r.isPass()).isTrue();
        assertThat(r.getWarning()).contains("结果含负值").contains("gmv").contains("-12.5");
    }

    @Test
    @DisplayName("负值在容差内（-0.001 浮点尾巴）：不告警，避免比值题天天响")
    void ignoresNegligibleNegative() {
        AgentContext ctx = ctxOf(row("pay_rate", new BigDecimal("-0.001")));

        GateResult r = gate.check(ctx);

        assertThat(r.isPass()).isTrue();
        assertThat(r.getWarning()).isNull();
    }

    @Test
    @DisplayName("空值比例超 20%：告警并给出百分比")
    void warnsOnHighNullRatio() {
        // 2 行 × 2 列 = 4 格，3 格为 NULL → 75%
        AgentContext ctx = ctxOf(
                row("channel_name", "小程序", "gmv", null),
                row("channel_name", null, "gmv", null));

        GateResult r = gate.check(ctx);

        assertThat(r.isPass()).isTrue();
        assertThat(r.getWarning()).contains("空值比例 75%").contains("20%");
    }

    @Test
    @DisplayName("空值比例在告警线下：不告警")
    void silentWhenNullRatioBelowLine() {
        // 5 行 × 2 列 = 10 格，1 格 NULL → 10%
        AgentContext ctx = ctxOf(
                row("channel_name", "小程序", "gmv", new BigDecimal("100")),
                row("channel_name", null, "gmv", new BigDecimal("90")),
                row("channel_name", "APP", "gmv", new BigDecimal("80")),
                row("channel_name", "H5", "gmv", new BigDecimal("70")),
                row("channel_name", "其他", "gmv", new BigDecimal("60")));

        GateResult r = gate.check(ctx);

        assertThat(r.isPass()).isTrue();
        assertThat(r.getWarning()).isNull();
    }

    @Test
    @DisplayName("字符串列不参与数值判定：日期/中文不会被硬 parse 成数字")
    void nonNumericValuesAreNotParsed() {
        AgentContext ctx = ctxOf(row("dt", "2026-10-19", "channel_name", "小程序", "gmv", new BigDecimal("100")));

        GateResult r = gate.check(ctx);

        assertThat(r.isPass()).isTrue();
        assertThat(r.getWarning()).isNull();
    }

    @Test
    @DisplayName("行数超 max_rows 仍按老口径截断（G1 不改这条）")
    void stillTruncatesRows() {
        // 替身里 max_rows=1000，构造 1001 行才能触发
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            many.add(row("gmv", new BigDecimal("1")));
        }
        AgentContext ctx = new AgentContext();
        ctx.setQueryResult(many);

        GateResult r = gate.check(ctx);

        assertThat(r.isPass()).isTrue();
        assertThat(r.getWarning()).isNull();
        assertThat(ctx.getQueryResult()).hasSize(1000);
    }

    // ---------------- 夹具 ----------------

    /** 按 key/value 交替传参，省掉一堆 HashMap 样板 */
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @SafeVarargs
    private static AgentContext ctxOf(Map<String, Object>... rows) {
        AgentContext ctx = new AgentContext();
        ctx.setQueryResult(new ArrayList<>(List.of(rows)));
        return ctx;
    }
}
