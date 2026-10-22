package com.zlf.datapilot.agent.step;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.analysis.ResultAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExplainStep 单测（Ticket 11）：图表类型按形状判定 + 答案不被覆盖。
 *
 * <p>「不覆盖」这条是关键：结果自检的「无数据」和环比的「前 7 天无数据」都写在 answer 里，
 * 解释节点如果再写一句「共 0 行」，用户拿到的就是不完整的结论。
 *
 * <p>Ticket 21 起这个节点还产出 {@code ctx.analysis}（结论 + 缺口），分析自身的算法细节
 * 由 {@code ResultAnalyzerTest} 覆盖，这里只验「接上了、且不影响 answer 的既有约定」。
 */
class ExplainStepTest {

    private final ExplainStep step = new ExplainStep(new ResultAnalyzer());

    private static AgentContext ctx(List<Map<String, Object>> rows) {
        AgentContext c = new AgentContext();
        c.setQueryResult(rows);
        return c;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("单行单列 → metric，答案给出「列名 = 值」")
    void singleValueIsMetric() {
        AgentContext c = ctx(List.of(row("gmv_total", 53042.41)));
        step.execute(c);

        assertThat(c.getChartType()).isEqualTo("metric");
        assertThat(c.getAnswer()).isEqualTo("gmv_total = 53042.41");
    }

    @Test
    @DisplayName("多行单列 → bar")
    void multiRowSingleColumnIsBar() {
        AgentContext c = ctx(List.of(row("province_name", "上海"), row("province_name", "江苏")));
        step.execute(c);

        assertThat(c.getChartType()).isEqualTo("bar");
    }

    @Test
    @DisplayName("两列且第一列是日期 → line（趋势）")
    void twoColumnsWithDateIsLine() {
        AgentContext c = ctx(List.of(row("dt", "2026-10-18", "gmv", 1), row("dt", "2026-10-19", "gmv", 2)));
        step.execute(c);

        assertThat(c.getChartType()).isEqualTo("line");
    }

    @Test
    @DisplayName("两列但第一列不是日期 → bar")
    void twoColumnsWithoutDateIsBar() {
        AgentContext c = ctx(List.of(row("channel_name", "APP", "rate", 0.1)));
        step.execute(c);

        assertThat(c.getChartType()).isEqualTo("bar");
    }

    @Test
    @DisplayName("三列及以上 → table")
    void threeOrMoreColumnsIsTable() {
        AgentContext c = ctx(List.of(row("a", 1, "b", 2, "c", 3)));
        step.execute(c);

        assertThat(c.getChartType()).isEqualTo("table");
    }

    @Test
    @DisplayName("空结果 → table，且答案保持为空（交给结果自检的「无数据」文案）")
    void emptyRowsKeepsGateAnswer() {
        AgentContext c = ctx(new ArrayList<>());
        c.setAnswer("无数据：查询结果为空，请检查筛选条件");

        step.execute(c);

        assertThat(c.getChartType()).isEqualTo("table");
        assertThat(c.getAnswer()).isEqualTo("无数据：查询结果为空，请检查筛选条件");
    }

    @Test
    @DisplayName("已有环比结论时不被覆盖（answer 只补空的）")
    void existingAnswerIsNotOverwritten() {
        AgentContext c = ctx(List.of(row("paid_order_cnt", 69)));
        c.setAnswer("本期：69；环比：前 7 天无数据，无法计算环比");

        step.execute(c);

        assertThat(c.getChartType()).isEqualTo("metric");
        assertThat(c.getAnswer()).isEqualTo("本期：69；环比：前 7 天无数据，无法计算环比");
    }

    @Test
    @DisplayName("Ticket 22：多行结果也用一句判断作答，不再念「共 7 行：n1=1、n2=2…」")
    void multiRowAnswerUsesVerdict() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            rows.add(row("name", "n" + i, "value", i));
        }
        AgentContext c = ctx(rows);
        step.execute(c);

        // 1+2+…+7=28；最大项 n7 占 25.0%。ASCII 标签后补空格，中文标签不留（两种都不难看）
        assertThat(c.getAnswer()).isEqualTo("value合计 28，n7 为最大项（占 25.0%）");
        // 明细仍在分析区，没丢
        assertThat(c.getAnalysis().findings().toString()).contains("28");
    }

    @Test
    @DisplayName("没有维度列时退回「共 N 行」：单值清单没有判断可说，念数字才是诚实的")
    void numericOnlyRowsFallBackToRowCount() {
        // 两列都是数值 → 没有维度列，占比/集中度都无从谈起
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            rows.add(row("a", i, "b", i * 2));
        }
        AgentContext c = ctx(rows);
        step.execute(c);

        assertThat(c.getAnswer()).startsWith("共 7 行");
        assertThat(c.getAnalysis().findings()).isEmpty();
    }

    @Test
    @DisplayName("Ticket 21/22：analysis 被写入；answer 换成一句判断，findings 仍有明细")
    void analysisIsFilledAndAnswerBecomesVerdict() {
        AgentContext c = ctx(List.of(
                row("category_name", "电脑外设", "sale_cnt_product", 58),
                row("category_name", "办公家具", "sale_cnt_product", 34)));
        step.execute(c);

        // Ticket 22：answer 不再念「共 2 行」，而是给出判断。
        // 本用例没挂 recalledMetadata，所以指标名回落成 metricId（不猜中文名）
        assertThat(c.getAnswer()).isEqualTo("sale_cnt_product合计 92，电脑外设为最大项（占 63.0%）");
        // 量化明细另走一条通道，没有被 answer 取代
        assertThat(c.getAnalysis().findings()).isNotEmpty();
        assertThat(c.getAnalysis().findings().toString()).contains("63.0%");
    }

    @Test
    @DisplayName("Ticket 21：空结果时分析为空（前端不渲染分析区），不会给出「合计 0」这种假结论")
    void emptyRowsProducesEmptyAnalysis() {
        AgentContext c = ctx(new ArrayList<>());
        step.execute(c);

        assertThat(c.getAnalysis().isEmpty()).isTrue();
    }
}
