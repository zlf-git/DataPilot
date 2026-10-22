package com.zlf.datapilot.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.ir.Filter;
import com.zlf.datapilot.ir.FilterOp;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.sql.SqlCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReportStep 单测（Ticket 11 建，Ticket 18 扩）：判据是硬的两条 —— <b>含 echarts</b>、
 * <b>series 长度 = rows 长度</b>（审计 P1#9 把「非空 html」这条废掉了）。
 *
 * <p>所以这里从页面里把 option JSON 抠出来解析后再断言 series 数量，而不是 contains("series")：
 * 后者只要模板里写了这个单词就绿，等于没测。
 *
 * <p>Ticket 18 补的四条对着两个线上问题：<b>图表永远空白</b>（CDN 连不上）与
 * <b>单指标报告只有一个数</b>（metric 被画成柱图）。
 */
class ReportStepTest {

    private final ObjectMapper mapper = new ObjectMapper();
    /** 单元测试不连库：指标口径取不到就降级成 metricId（报告是旁路产物，不能因此失败） */
    private final ReportStep step = new ReportStep(mapper, null);

    private static AgentContext ctx(List<Map<String, Object>> rows, String chartType) {
        AgentContext c = new AgentContext();
        c.setQueryResult(rows);
        c.setChartType(chartType);
        c.setUserQuestion("测试问题");
        c.setTraceId("trace123");
        return c;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 从 html 里抠出 `var option = {...};` 并解析 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> optionOf(String html) {
        int start = html.indexOf("var option = ") + "var option = ".length();
        int end = html.indexOf(";\n", start);
        try {
            return mapper.readValue(html.substring(start, end), Map.class);
        } catch (Exception e) {
            throw new AssertionError("option JSON 无法解析：" + e.getMessage(), e);
        }
    }

    @Test
    @DisplayName("3 行数据 → 3 条 series（判据：series 长度 = rows 长度）")
    void seriesCountEqualsRowCount() {
        List<Map<String, Object>> rows = List.of(
                row("channel_name", "APP", "rate", 0.1),
                row("channel_name", "小程序", "rate", 0.12),
                row("channel_name", "网页", "rate", 0.075));
        AgentContext c = ctx(rows, "bar");
        step.execute(c);

        String html = c.getReportHtml();
        assertThat(html).contains("echarts");
        List<Map<String, Object>> series = (List<Map<String, Object>>) optionOf(html).get("series");
        assertThat(series).hasSize(rows.size());
        assertThat(series).extracting(s -> s.get("name")).containsExactly("APP", "小程序", "网页");
    }

    @Test
    @DisplayName("单行单列（metric）→ 1 条 series，值就是那一格")
    void metricHasSingleSeries() {
        AgentContext c = ctx(List.of(row("gmv_total", 53042.41)), "metric");
        step.execute(c);

        List<Map<String, Object>> series = (List<Map<String, Object>>) optionOf(c.getReportHtml()).get("series");
        assertThat(series).hasSize(1);
        assertThat(series.get(0).get("data")).isEqualTo(List.of(53042.41));
    }

    @Test
    @DisplayName("空结果 → series 为空数组，页面仍完整（不抛异常、不出现坏 JS）")
    void emptyRowsProducesEmptySeries() {
        AgentContext c = ctx(new ArrayList<>(), "table");
        step.execute(c);

        String html = c.getReportHtml();
        assertThat(html).contains("echarts").contains("查询无数据");
        assertThat((List<Object>) optionOf(html).get("series")).isEmpty();
        assertThat(html).contains("</html>");
    }

    @Test
    @DisplayName("值里带引号/脚本闭合标签：不破坏 JS，脚本块不被提前闭合，表格里被转义")
    void specialCharsAreEscaped() {
        String evil = "a\"; alert(1); //</script><b>x</b>";
        AgentContext c = ctx(List.of(row("name", evil, "value", 1)), "bar");
        step.execute(c);

        String html = c.getReportHtml();
        // option JSON 仍可解析，且值经 unicode 转义后语义不变（说明引号没截断字符串）
        List<Map<String, Object>> series = (List<Map<String, Object>>) optionOf(html).get("series");
        assertThat(series).hasSize(1);
        assertThat(series.get(0).get("name")).isEqualTo(evil);
        // 值里的 </script> 必须已转义：页面里只剩「引入 echarts」与「本页脚本」两个闭合标签，
        // 且不出现 </script> 紧跟标签的截断形态
        assertThat(html.split("</script>", -1).length - 1).isEqualTo(2);
        assertThat(html).doesNotContain("</script><b>");
        // 表格里不能出现原样标签
        assertThat(html).doesNotContain("<b>x</b>");
        assertThat(html).contains("&lt;b&gt;");
    }

    @Test
    @DisplayName("报告里带 traceId 与图表类型，便于和 agent_run 对上")
    void reportCarriesTraceIdAndChartType() {
        AgentContext c = ctx(List.of(row("k", "v", "n", 1)), "bar");
        step.execute(c);

        assertThat(c.getReportHtml()).contains("trace123").contains("bar");
    }

    // ---------------- Ticket 18 ----------------

    @Test
    @DisplayName("Ticket 18：图脚本同源本地化，页面不再引用任何外网资源")
    void echartsIsServedLocally() {
        AgentContext c = ctx(List.of(row("channel_name", "APP", "gmv", 1)), "bar");
        step.execute(c);

        String html = c.getReportHtml();
        // 这是「图表永远空白」的根因：原实现指向 cdn.jsdelivr.net，本机连不上 → echarts 恒 undefined
        assertThat(html).contains("src=\"/report-assets/echarts.min.js\"");
        assertThat(html).doesNotContain("jsdelivr").doesNotContain("https://").doesNotContain("http://");
    }

    @Test
    @DisplayName("Ticket 18：metric 渲染大数字卡片，不再留一块空图容器")
    void metricRendersCardInsteadOfChart() {
        AgentContext c = ctx(List.of(row("gmv_channel", 15604.97)), "metric");
        step.execute(c);

        String html = c.getReportHtml();
        assertThat(html).contains("class=\"card\"");
        assertThat(html).contains("15604.97").contains("gmv_channel");
        // 没有图容器 → 不会出现「图区空白」那种视觉
        assertThat(html).doesNotContain("id=\"chart\"");
        // 非 metric 仍要有图容器，避免一刀切删图
        AgentContext bar = ctx(List.of(row("channel_name", "APP", "gmv", 1)), "bar");
        step.execute(bar);
        assertThat(bar.getReportHtml()).contains("id=\"chart\"");
    }

    @Test
    @DisplayName("Ticket 18：报告写明口径/数据源/过滤条件/结果规模，不只给一个数")
    void reportExplainsWhereTheNumberComesFrom() {
        AgentContext c = ctx(List.of(row("gmv_channel", 15604.97)), "metric");
        QueryIr ir = new QueryIr();
        MetricRef ref = new MetricRef();
        ref.setMetricId("gmv_channel");
        ir.setMetrics(new ArrayList<>(List.of(ref)));
        Filter f1 = new Filter();
        f1.setField("dt");
        f1.setOp(FilterOp.EQ);
        f1.setValue("2026-10-19");
        Filter f2 = new Filter();
        f2.setField("channel_name");
        f2.setOp(FilterOp.EQ);
        f2.setValue("小程序");
        ir.setFilters(new ArrayList<>(List.of(f1, f2)));
        c.setIr(ir);
        c.addGateResult("存在性=PASS");
        c.addGateResult("权限=PASS");
        c.addGateResult("代价=PASS");
        c.addGateResult("语法=PASS");
        c.addGateResult("结果自检=PASS");

        step.execute(c);
        String html = c.getReportHtml();

        assertThat(html).contains("口径与查询条件").contains("结果明细");
        assertThat(html).contains("gmv_channel");            // 指标（口径取不到时降级成 id）
        assertThat(html).contains("dt = 2026-10-19");
        assertThat(html).contains("channel_name = 小程序");
        assertThat(html).contains("1 行 × 1 列");
        assertThat(html).contains("结果自检=PASS");
    }

    @Test
    @DisplayName("Ticket 18：SQL 折叠区带参数，报告能自证这个数来自哪条 SQL")
    void reportCarriesSqlAndParams() {
        AgentContext c = ctx(List.of(row("gmv_channel", 15604.97)), "metric");
        c.setGeneratedSql("SELECT SUM(gmv) AS gmv_channel FROM ads_channel_conversion_di WHERE dt = ?");
        c.setSqlCommand(SqlCommand.of("SELECT 1", List.of("2026-10-19", "小程序")));

        step.execute(c);
        String html = c.getReportHtml();

        assertThat(html).contains("<details>").contains("生成的 SQL");
        assertThat(html).contains("ads_channel_conversion_di");
        assertThat(html).contains("绑定参数").contains("2026-10-19");
    }
}
