package com.zlf.datapilot.sql;

import com.zlf.datapilot.ir.DimensionRef;
import com.zlf.datapilot.ir.Filter;
import com.zlf.datapilot.ir.FilterOp;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.ir.RatioRef;
import com.zlf.datapilot.ir.SelectMode;
import com.zlf.datapilot.knowledge.MetadataRepository;
import com.zlf.datapilot.knowledge.OntologyCache;
import com.zlf.datapilot.support.TestOntology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IrToSqlConverter 单测（Ticket 06c 任务 1）：只断言外部行为 —— 占位符个数/参数顺序/SQL 形状，
 * 不碰私有方法。白名单用 {@link TestOntology} 替身（不连库）。
 *
 * <p>覆盖 QA-05 遗留 U1（分母为 0 的 NULLIF 分支）、U2（分母参数顺序）、U3（锚点只取一次）：
 * U1 只能锁 SQL 形状（值为 NULL 需真引擎，见 ticket 06c 落地记录）；
 * U2 在本设计下"把两组相同参数写反"不可观测，但"from/anchor 写反"可观测，故断言完整参数列表；
 * U3 断言 anchorProvider 只被调用一次 —— 分母复用主表时间条件，不存在第二套锚点。
 */
class IrToSqlConverterTest {

    private MetadataRepository repo;
    private OntologyCache cache;
    private TimeAnchorProvider anchorProvider;
    private IrToSqlConverter converter;

    @BeforeEach
    void setUp() {
        cache = TestOntology.cache();
        Map<String, com.zlf.datapilot.knowledge.model.MetricDef> metrics = TestOntology.metrics();
        repo = mock(MetadataRepository.class);
        metrics.forEach((id, def) -> when(repo.findByMetricId(id)).thenReturn(def));
        anchorProvider = mock(TimeAnchorProvider.class);
        when(anchorProvider.anchor(anyString())).thenReturn(TestOntology.ANCHOR);
        converter = new IrToSqlConverter(repo, cache, anchorProvider);
    }

    // ---------------- 任务 1：= / IN / LIKE 的参数绑定 ----------------

    @Test
    @DisplayName("= 过滤：1 个占位符 + 1 个参数")
    void eqBindsSinglePlaceholder() {
        QueryIr ir = agg("gmv_total", filter("dt", FilterOp.EQ, "2026-10-19"));

        SqlCommand cmd = converter.convert(ir);

        assertThat(placeholders(cmd.sql())).isEqualTo(1);
        assertThat(cmd.params()).containsExactly("2026-10-19");
        assertThat(cmd.sql()).contains("WHERE dt = ?").doesNotContain("2026-10-19");
    }

    @Test
    @DisplayName("IN 过滤：n 个值出 n 个占位符、n 个参数")
    void inBindsNPlaceholders() {
        QueryIr ir = agg("gmv_channel", filter("channel_name", FilterOp.IN, List.of("APP", "网页", "小程序")));

        SqlCommand cmd = converter.convert(ir);

        assertThat(placeholders(cmd.sql())).isEqualTo(3);
        assertThat(cmd.sql()).contains("channel_name IN (?, ?, ?)");
        assertThat(cmd.params()).containsExactly("APP", "网页", "小程序");
    }

    @Test
    @DisplayName("IN 过滤：逗号串显式拒绝（不按逗号硬切）")
    void inRejectsCommaJoinedString() {
        QueryIr ir = agg("gmv_channel", filter("channel_name", FilterOp.IN, "APP,网页"));

        assertThatThrownBy(() -> converter.convert(ir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("含逗号")
                .hasMessageContaining("JSON 数组");
    }

    @Test
    @DisplayName("LIKE 过滤：值两侧包 %，仍是 1 个占位符")
    void likeWrapsPercent() {
        QueryIr ir = agg("sale_cnt_product", filter("product_name", FilterOp.LIKE, "键盘"));

        SqlCommand cmd = converter.convert(ir);

        assertThat(placeholders(cmd.sql())).isEqualTo(1);
        assertThat(cmd.params()).containsExactly("%键盘%");
        assertThat(cmd.sql()).contains("product_name LIKE ?");
    }

    // ---------------- 任务 1 延伸：复合指标（QA-05 U1/U2/U3） ----------------

    @Test
    @DisplayName("同表复合指标：ROUND(分子/NULLIF(分母,0),4)，只出一列（U1）")
    void sameTableCompositeUsesNullIfZero() {
        QueryIr ir = agg("pay_conversion_rate", filter("dt", FilterOp.EQ, "2026-10-19"));

        SqlCommand cmd = converter.convert(ir);

        assertThat(cmd.sql()).isEqualTo(
                "SELECT ROUND(SUM(paid_order_cnt) / NULLIF(SUM(visit_uv), 0), 4) AS pay_conversion_rate"
                        + " FROM ads_channel_conversion_di WHERE dt = ? LIMIT 1000");
        assertThat(selectColumnCount(cmd.sql())).isEqualTo(1);
        assertThat(cmd.params()).containsExactly("2026-10-19");
    }

    @Test
    @DisplayName("跨表复合指标：分母走标量子查询，不生成 JOIN")
    void crossTableCompositeUsesScalarSubquery() {
        QueryIr ir = agg("gmv_channel_ratio", filter("dt", FilterOp.EQ, "2026-10-19"));

        SqlCommand cmd = converter.convert(ir);

        assertThat(cmd.sql()).contains(
                "ROUND(SUM(gmv) / NULLIF((SELECT SUM(total_gmv) FROM ads_trade_overview_di WHERE dt = ?), 0), 4)"
                        + " AS gmv_channel_ratio");
        assertThat(cmd.sql().toUpperCase()).doesNotContain("JOIN");
        assertThat(selectColumnCount(cmd.sql())).isEqualTo(1);
    }

    @Test
    @DisplayName("跨表复合指标：参数顺序 = 占位符顺序（分母先，主查询后）（U2）")
    void crossTableCompositeParamOrderMatchesPlaceholders() {
        QueryIr ir = agg("gmv_channel_ratio", null);
        ir.setTimeRange(window(7));

        SqlCommand cmd = converter.convert(ir);

        assertThat(placeholders(cmd.sql())).isEqualTo(4);
        assertThat(cmd.params()).containsExactly(
                "2026-10-12", TestOntology.ANCHOR, "2026-10-12", TestOntology.ANCHOR);
        // 两处时间条件：分母子查询一处、主查询一处；子查询文本在前，故前两个 ? 属于分母
        assertThat(countOf(cmd.sql(), "WHERE dt > ? AND dt <= ?")).isEqualTo(2);
        assertThat(cmd.sql().indexOf("(SELECT")).isLessThan(cmd.sql().lastIndexOf("WHERE dt > ?"));
    }

    @Test
    @DisplayName("跨表复合指标：锚点只取一次，分母复用主表时间条件（U3）")
    void crossTableCompositeRequestsAnchorOnce() {
        QueryIr ir = agg("gmv_channel_ratio", null);
        ir.setTimeRange(window(7));

        SqlCommand cmd = converter.convert(ir);

        verify(anchorProvider, times(1)).anchor(anyString());
        assertThat(cmd.sql()).contains("SELECT SUM(total_gmv) FROM ads_trade_overview_di WHERE dt > ? AND dt <= ?");
    }

    @Test
    @DisplayName("跨表维度：显式拒绝，不放到 SQL 层才炸")
    void crossTableDimensionRejected() {
        QueryIr ir = agg("gmv_total", filter("dt", FilterOp.EQ, "2026-10-19"));
        DimensionRef d = new DimensionRef();
        d.setField("channel_name");
        ir.setDimensions(new ArrayList<>(List.of(d)));

        assertThatThrownBy(() -> converter.convert(ir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于表 " + TestOntology.OVERVIEW);
    }

    @Test
    @DisplayName("旧 ratio 字段：拒绝并指向复合指标（不给「指标不存在」的误导）")
    void ratioFieldRejectedWithActionableHint() {
        QueryIr ir = agg("gmv_total", filter("dt", FilterOp.EQ, "2026-10-19"));
        RatioRef ratio = new RatioRef();
        ratio.setNumerator("gmv");
        ratio.setDenominator("gmv_total");
        ir.setRatio(ratio);

        assertThatThrownBy(() -> converter.convert(ir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("复合指标")
                .hasMessageContaining("ratio{");
    }

    @Test
    @DisplayName("多指标：显式拒绝（Ticket 05 只做单指标）")
    void multipleMetricsRejected() {
        QueryIr ir = agg("gmv_total", null);
        MetricRef second = new MetricRef();
        second.setMetricId("gmv_channel");
        ir.setMetrics(new ArrayList<>(List.of(ir.getMetrics().get(0), second)));

        assertThatThrownBy(() -> converter.convert(ir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多指标暂不支持");
    }

    // ---------------- 夹具 ----------------

    private static QueryIr agg(String metricId, Filter f) {
        QueryIr ir = new QueryIr();
        ir.setSelectMode(SelectMode.AGG);
        MetricRef m = new MetricRef();
        m.setMetricId(metricId);
        ir.setMetrics(new ArrayList<>(List.of(m)));
        ir.setDimensions(new ArrayList<>());
        ir.setFilters(new ArrayList<>(f == null ? List.of() : List.of(f)));
        ir.setOrderBy(new ArrayList<>());
        return ir;
    }

    private static Filter filter(String field, FilterOp op, Object value) {
        Filter f = new Filter();
        f.setField(field);
        f.setOp(op);
        f.setValue(value);
        return f;
    }

    private static com.zlf.datapilot.ir.TimeRange window(int days) {
        com.zlf.datapilot.ir.TimeRange tr = new com.zlf.datapilot.ir.TimeRange();
        tr.setRelativeWindow("LAST_N_DAYS");
        tr.setDays(days);
        return tr;
    }

    private static int placeholders(String sql) {
        return (int) sql.chars().filter(c -> c == '?').count();
    }

    private static int countOf(String text, String needle) {
        int n = 0;
        int i = text.indexOf(needle);
        while (i >= 0) {
            n++;
            i = text.indexOf(needle, i + needle.length());
        }
        return n;
    }

    /** SELECT 列表的表达式个数（按顶层逗号切，括号内的逗号不算） */
    private static int selectColumnCount(String sql) {
        String body = sql.substring("SELECT ".length(), sql.indexOf(" FROM "));
        int depth = 0;
        int n = 1;
        for (char c : body.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                n++;
            }
        }
        return n;
    }
}
