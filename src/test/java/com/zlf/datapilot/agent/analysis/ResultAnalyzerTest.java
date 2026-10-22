package com.zlf.datapilot.agent.analysis;

import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.knowledge.model.MetricDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResultAnalyzer 单测（Ticket 21）。
 *
 * <p>两类断言各有侧重：
 * <ul>
 *   <li><b>算得对</b>：占比、均值偏离、极值比都用实数逐字核对 —— 分析器编不出数字，
 *       所以每个数字都必须能从 rows 反推出来。</li>
 *   <li><b>答不了要说出来</b>：缺口检测是最容易被写歪的部分（写宽了天天报缺，写窄了等于没有），
 *       这里把「该报」和「不该报」都钉住。</li>
 * </ul>
 */
class ResultAnalyzerTest {

    private final ResultAnalyzer analyzer = new ResultAnalyzer();

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static MetricDef metric(String id, String name, String sql) {
        MetricDef d = new MetricDef();
        d.setMetricId(id);
        d.setMetricName(name);
        d.setMetricSql(sql);
        return d;
    }

    private static QueryIr irOf(String... metricIds) {
        QueryIr ir = new QueryIr();
        for (String id : metricIds) {
            MetricRef ref = new MetricRef();
            ref.setMetricId(id);
            ir.getMetrics().add(ref);
        }
        return ir;
    }

    /** 复现截图那一幕：两品类销量 58 / 34 */
    private static List<Map<String, Object>> twoCategories() {
        return List.of(
                row("category_name", "电脑外设", "sale_cnt_product", 58),
                row("category_name", "办公家具", "sale_cnt_product", 34));
    }

    private static final List<MetricDef> PRODUCT_METRICS = List.of(
            metric("sale_cnt_product", "商品销量", "SUM(sale_cnt)"));

    @Test
    @DisplayName("按维度分组：算出占比、均值偏离、极值比；两项不谈集中（任一项必然过半）")
    void distributionFindings() {
        Analysis a = analyzer.analyze(twoCategories(), irOf("sale_cnt_product"),
                PRODUCT_METRICS, "各品类销量情况");

        // Ticket 22：只剩 3 条 —— 原第 2 条「集中在电脑外设（占 63%），结构偏集中」被删。
        // 两项构成里任一项必然 ≥50%，那句话永远成立，等于把第 1 条的「占 63.0%」换个说法再说一遍
        assertThat(a.findings()).hasSize(3);
        // 58+34=92；58/92=63.0%，34/92=37.0%
        assertThat(a.findings().get(0))
                .isEqualTo("商品销量合计 92；电脑外设 58 占 63.0%，办公家具 34 占 37.0%。");
        // 均值 46，58 高 26.1%，34 低 26.1%
        assertThat(a.findings().get(1)).isEqualTo("相对均值 46：电脑外设高 26.1%，办公家具低 26.1%。");
        // 58/34=1.71
        assertThat(a.findings().get(2)).contains("极值相差 1.71 倍");
        // 两项分布不该出现「集中/分散」这类判断
        assertThat(a.findings()).noneMatch(f -> f.contains("集中在") || f.contains("较分散"));
        // 一句话结论：兜底判定用「合计 + 头部项」
        assertThat(a.summary()).isEqualTo("商品销量合计 92，电脑外设为最大项（占 63.0%）");
    }

    @Test
    @DisplayName("指标中文名来自 metric_def；查不到就显示 metricId，不猜语义")
    void metricNameLookupFallsBackToId() {
        Analysis withName = analyzer.analyze(twoCategories(), irOf("sale_cnt_product"),
                PRODUCT_METRICS, null);
        assertThat(withName.findings().get(0)).startsWith("商品销量合计");

        Analysis noDef = analyzer.analyze(twoCategories(), irOf("sale_cnt_product"), List.of(), null);
        assertThat(noDef.findings().get(0)).startsWith("sale_cnt_product合计");
    }

    @Test
    @DisplayName("缺口：问「金额占比」但只取了销量 → 必须明说金额没取")
    void gapWhenMoneyAskedButNotTaken() {
        Analysis a = analyzer.analyze(twoCategories(), irOf("sale_cnt_product"), PRODUCT_METRICS,
                "各商品品类销量占比与金额占比是否匹配？");

        assertThat(a.gaps()).isNotEmpty();
        assertThat(a.gaps().toString()).contains("金额").contains("sale_cnt_product");
        // 「匹配」要求两量对照，只有一个指标 → 也要报
        assertThat(a.gaps().toString()).contains("没有可对照的第二项");
    }

    @Test
    @DisplayName("缺口：件单价 = 金额 ÷ 数量，缺任一个都算不出来，且要说清缺哪个")
    void gapWhenUnitPriceAsked() {
        Analysis onlyCount = analyzer.analyze(twoCategories(), irOf("sale_cnt_product"),
                PRODUCT_METRICS, "各商品件单价相对大盘客单价是偏高还是偏低？");
        assertThat(onlyCount.gaps().toString()).contains("件单价").contains("缺金额");

        List<MetricDef> moneyMetrics = List.of(
                metric("sale_amount_product", "商品销售额", "SUM(sale_amount)"));
        List<Map<String, Object>> moneyRows = List.of(
                row("category_name", "电脑外设", "sale_amount_product", 1000));
        Analysis onlyMoney = analyzer.analyze(moneyRows, irOf("sale_amount_product"),
                moneyMetrics, "各商品件单价是偏高还是偏低？");
        assertThat(onlyMoney.gaps().toString()).contains("缺数量");
    }

    @Test
    @DisplayName("缺口不该乱报：金额与数量都取回了，就不再提缺金额/缺数量")
    void noGapWhenBothTaken() {
        List<MetricDef> both = List.of(
                metric("sale_amount_product", "商品销售额", "SUM(sale_amount)"),
                metric("sale_cnt_product", "商品销量", "SUM(sale_cnt)"));
        List<Map<String, Object>> rows = List.of(
                row("category_name", "电脑外设", "sale_amount_product", 1000, "sale_cnt_product", 10));
        Analysis a = analyzer.analyze(rows, irOf("sale_amount_product", "sale_cnt_product"),
                both, "各品类销量与金额是否匹配？");

        assertThat(a.gaps()).isEmpty();
    }

    @Test
    @DisplayName("时间序列：算首尾变化与最近一期环比，而不是只把明细列出来")
    void temporalFindings() {
        List<Map<String, Object>> rows = List.of(
                row("dt", "2026-10-19", "gmv_total", 100),
                row("dt", "2026-10-20", "gmv_total", 150),
                row("dt", "2026-10-21", "gmv_total", 120));
        Analysis a = analyzer.analyze(rows, irOf("gmv_total"),
                List.of(metric("gmv_total", "GMV", "SUM(total_gmv)")), "GMV 趋势如何");

        assertThat(a.findings().toString())
                .contains("从 2026-10-19 的 100 变为 2026-10-21 的 120")
                .contains("上升 20.0%");
        // 最近一期：150 → 120，下降 20.0%
        assertThat(a.findings().toString()).contains("最近一期环比").contains("下降 20.0%");
        assertThat(a.findings().toString()).contains("峰值 150").contains("谷值 100");
    }

    @Test
    @DisplayName("日期乱序也要按时间算，不假设调用方给了正序")
    void temporalSortsByDate() {
        List<Map<String, Object>> rows = List.of(
                row("dt", "2026-10-21", "gmv_total", 120),
                row("dt", "2026-10-19", "gmv_total", 100));
        Analysis a = analyzer.analyze(rows, irOf("gmv_total"), List.of(), "趋势");

        assertThat(a.findings().toString()).contains("从 2026-10-19").contains("变为 2026-10-21");
    }

    @Test
    @DisplayName("空结果 → 空分析；纯文本结果（明细）→ 空分析，不硬凑结论")
    void noAnalysisForEmptyOrTextOnly() {
        assertThat(analyzer.analyze(new ArrayList<>(), null, null, "随便问").isEmpty()).isTrue();

        List<Map<String, Object>> textOnly = List.of(row("channel_name", "APP"), row("channel_name", "MP"));
        assertThat(analyzer.analyze(textOnly, null, null, "有哪些渠道").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("单行单列：没有可对比对象，不编占比也不编偏离")
    void singleValueHasNoDistribution() {
        Analysis a = analyzer.analyze(List.of(row("gmv_total", 53042.41)),
                irOf("gmv_total"), List.of(), null);

        assertThat(a.findings()).isEmpty();
        assertThat(a.gaps()).isEmpty();
    }

    @Test
    @DisplayName("问题为空时只做数值分析，不猜缺口")
    void blankQuestionSkipsGapDetection() {
        Analysis a = analyzer.analyze(twoCategories(), irOf("sale_cnt_product"),
                PRODUCT_METRICS, null);

        assertThat(a.findings()).isNotEmpty();
        assertThat(a.gaps()).isEmpty();
    }

    @Test
    @DisplayName("派生：金额与数量都在时算出件单价，并与「整体比值」比（不是逐项比值的平均）")
    void derivedUnitPrice() {
        List<Map<String, Object>> rows = List.of(
                row("category_name", "电脑外设", "sale_amount_product", 5800, "sale_cnt_product", 58),
                row("category_name", "办公家具", "sale_amount_product", 2380, "sale_cnt_product", 34));
        List<MetricDef> defs = List.of(
                metric("sale_amount_product", "商品销售额", "SUM(sale_amount)"),
                metric("sale_cnt_product", "商品销量", "SUM(sale_cnt)"));
        Analysis a = analyzer.analyze(rows, irOf("sale_amount_product", "sale_cnt_product"),
                defs, "每个商品的件单价相对大盘客单价是偏高还是偏低？");

        // 整体 = 8180/92 = 88.91（不是 (100+70)/2 = 85）
        assertThat(a.findings()).anyMatch(f ->
                f.startsWith("件单价（商品销售额 ÷ 商品销量）整体为 88.91")
                        && f.contains("电脑外设 100") && f.contains("办公家具 70"));
        // 100 vs 88.91 → +12.5%；70 vs 88.91 → -21.3%
        assertThat(a.findings()).anyMatch(f ->
                f.contains("电脑外设高于整体 12.5%") && f.contains("办公家具低于整体 21.3%"));
        // 两个量都取回了，单价这次算得出来，不该再报缺金额/缺数量
        assertThat(a.gaps()).noneMatch(g -> g.contains("件单价"));
    }

    @Test
    @DisplayName("偏离都不显著时也要说话：「基本持平」本身就是对「偏高还是偏低」的回答")
    void derivedFlatWhenWithinThreshold() {
        // 两项单价都是 100，整体也是 100 → 零偏离
        List<Map<String, Object>> rows = List.of(
                row("name", "a", "sale_amount", 1000, "sale_cnt", 10),
                row("name", "b", "sale_amount", 3000, "sale_cnt", 30));
        Analysis a = analyzer.analyze(rows, null, null, "各商品单价是偏高还是偏低？");

        assertThat(a.findings()).anyMatch(f -> f.contains("基本持平"));
        assertThat(a.findings()).noneMatch(f -> f.contains("高于整体") || f.contains("低于整体"));
    }

    @Test
    @DisplayName("份额对照：直接算出两列份额差（pp）并给一致性判定，不让人自己心算")
    void shareMismatchIsJudged() {
        // 销量 58/34（63.0% / 37.0%），金额 5800/2380（70.9% / 29.1%）→ 差 7.9pp
        List<Map<String, Object>> rows = List.of(
                row("category_name", "电脑外设", "sale_cnt_product", 58, "sale_amount_product", 5800),
                row("category_name", "办公家具", "sale_cnt_product", 34, "sale_amount_product", 2380));
        Analysis a = analyzer.analyze(rows, irOf("sale_cnt_product", "sale_amount_product"),
                List.of(), "销量占比与金额占比是否匹配？");

        // 份额对照排在最前：它是问题的主句
        assertThat(a.findings().get(0)).contains("份额对照").contains("pp");
        // 销量份额 63.0% - 金额份额 70.9% = -7.9pp（这一项销量占比低于金额占比，说明单价更高）
        assertThat(a.findings().get(0)).contains("电脑外设 -7.9pp");
        // 7.9pp 落在「有差异但不悬殊」区间
        assertThat(a.findings().get(1)).contains("存在结构性差异但不悬殊");
    }

    @Test
    @DisplayName("份额几乎相同时判定为「基本一致」，不硬说成差异")
    void shareMismatchWithinTolerance() {
        List<Map<String, Object>> rows = List.of(
                row("name", "a", "v1", 630, "v2", 652),
                row("name", "b", "v1", 370, "v2", 348));
        Analysis a = analyzer.analyze(rows, null, null, "两个占比是否匹配");

        // 63.0% vs 65.2% → 2.2pp ≤ 3pp
        assertThat(a.findings().get(1)).contains("基本一致");
    }

    @Test
    @DisplayName("说清「整体值」不等于「大盘」：否则相对大盘的结论会被误读成已经做过")
    void benchmarkGapWhenTalkingAboutOverall() {
        List<Map<String, Object>> rows = List.of(
                row("category_name", "电脑外设", "sale_amount_product", 5800, "sale_cnt_product", 58),
                row("category_name", "办公家具", "sale_amount_product", 2380, "sale_cnt_product", 34));
        List<MetricDef> defs = List.of(
                metric("sale_amount_product", "商品销售额", "SUM(sale_amount)"),
                metric("sale_cnt_product", "商品销量", "SUM(sale_cnt)"));
        Analysis a = analyzer.analyze(rows, irOf("sale_amount_product", "sale_cnt_product"),
                defs, "每个商品的件单价相对大盘客单价是偏高还是偏低？");

        assertThat(a.gaps().toString()).contains("大盘").contains("不是另一张表的大盘口径");
    }

    @Test
    @DisplayName("派生有边界：只有一列时不硬造比值；两列都是金额/都是数量时也不除")
    void derivedNeedsOneMoneyOneCount() {
        // 只有金额列
        Analysis onlyMoney = analyzer.analyze(
                List.of(row("name", "a", "sale_amount_product", 100)),
                irOf("sale_amount_product"),
                List.of(metric("sale_amount_product", "商品销售额", "SUM(sale_amount)")), null);
        assertThat(onlyMoney.findings()).noneMatch(f -> f.contains("÷"));

        // 两列都是金额：不能把 GMV 和销售额相除
        Analysis twoMoney = analyzer.analyze(
                List.of(row("name", "a", "gmv_total", 100, "sale_amount_product", 200)),
                irOf("gmv_total", "sale_amount_product"), List.of(), null);
        assertThat(twoMoney.findings()).noneMatch(f -> f.contains("÷"));
    }

    @Test
    @DisplayName("结论条数有上限：10 个维度也不该输出 10 条，否则又变成一份明细")
    void findingsAreCapped() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(row("name", "n" + i, "v", i + 1));
        }
        Analysis a = analyzer.analyze(rows, null, null, null);

        assertThat(a.findings()).hasSizeLessThanOrEqualTo(6);
    }
}
