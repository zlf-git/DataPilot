package com.zlf.datapilot.sql;

import com.zlf.datapilot.ir.Filter;
import com.zlf.datapilot.ir.FilterOp;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.ir.SelectMode;
import com.zlf.datapilot.ir.TimeRange;
import com.zlf.datapilot.knowledge.MetadataRepository;
import com.zlf.datapilot.knowledge.OntologyCache;
import com.zlf.datapilot.support.TestOntology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 时间窗口单测（Ticket 06c 任务 3）：断言外部行为 —— 参数值、SQL 形状、拒绝文案。
 *
 * <p>核心判据：<b>10-13 命中、10-12 不命中</b>。这批数据里 2026-10-12 是空分区，
 * 闭区间与半开区间逐题返回值相同，只能从「参数 + 比较符号」上判别：
 * 一旦从 {@code dt > ?} 退化成 {@code dt >= ?}，10-12 就会被算进来（8 天），值仍可能一样但口径已错。
 */
class TimeRangeTest {

    private TimeAnchorProvider anchorProvider;
    private IrToSqlConverter converter;

    @BeforeEach
    void setUp() {
        OntologyCache cache = TestOntology.cache();
        Map<String, com.zlf.datapilot.knowledge.model.MetricDef> metrics = TestOntology.metrics();
        MetadataRepository repo = mock(MetadataRepository.class);
        metrics.forEach((id, def) -> when(repo.findByMetricId(id)).thenReturn(def));
        anchorProvider = mock(TimeAnchorProvider.class);
        when(anchorProvider.anchor(anyString())).thenReturn(TestOntology.ANCHOR);
        converter = new IrToSqlConverter(repo, cache, anchorProvider);
    }

    @Test
    @DisplayName("近 7 天：半开下界 10-12（不命中）+ 锚点 10-19（命中），SQL 无 BETWEEN")
    void sevenDaysIsHalfOpenInterval() {
        TimeRange tr = window(7, null);

        SqlCommand cmd = converter.convert(agg(tr));

        assertThat(cmd.params()).containsExactly("2026-10-12", "2026-10-19");
        assertThat(cmd.sql()).contains("dt > ?").contains("dt <= ?");
        assertThat(cmd.sql().toUpperCase()).doesNotContain("BETWEEN");
        // 退化成 >= 就会多吃 10-12，值可能不变但口径已错 —— 这里锁死比较符号
        assertThat(cmd.sql()).doesNotContain("dt >= ?").doesNotContain("dt < ?");
        assertThat(inWindow("2026-10-12", cmd)).isFalse();
        assertThat(inWindow("2026-10-13", cmd)).isTrue();
        assertThat(inWindow("2026-10-19", cmd)).isTrue();
        assertThat(inWindow("2026-10-20", cmd)).isFalse();
    }

    @Test
    @DisplayName("近 1 天：只取锚点当天（10-18 不命中、10-19 命中）")
    void oneDayOnlyCoversAnchor() {
        SqlCommand cmd = converter.convert(agg(window(1, null)));

        assertThat(cmd.params()).containsExactly("2026-10-18", "2026-10-19");
        assertThat(inWindow("2026-10-18", cmd)).isFalse();
        assertThat(inWindow("2026-10-19", cmd)).isTrue();
    }

    @Test
    @DisplayName("锚点由 MAX(dt) 回填到 IR（响应体要能回显实际窗口）")
    void anchorIsBackfilled() {
        TimeRange tr = window(7, null);

        converter.convert(agg(tr));

        assertThat(tr.getAnchor()).isEqualTo("2026-10-19");
    }

    @Test
    @DisplayName("锚点格式归一：20261019 视为 2026-10-19，不报「与数据不一致」")
    void compactAnchorNormalized() {
        SqlCommand cmd = converter.convert(agg(window(7, "20261019")));

        assertThat(cmd.params()).containsExactly("2026-10-12", "2026-10-19");
    }

    @Test
    @DisplayName("锚点与数据最新日不一致：显式拒绝")
    void anchorMismatchRejected() {
        assertThatThrownBy(() -> converter.convert(agg(window(7, "2026-10-18"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("与数据最新日期不一致")
                .hasMessageContaining("2026-10-18");
    }

    @Test
    @DisplayName("锚点格式非法：报「格式非法」，不冒充「与数据不一致」")
    void illegalAnchorFormatRejected() {
        assertThatThrownBy(() -> converter.convert(agg(window(7, "19/10/2026"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("格式非法");
    }

    @Test
    @DisplayName("缺 relativeWindow：显式拒绝（不静默按 LAST_N_DAYS 兜底）")
    void missingRelativeWindowRejected() {
        TimeRange tr = new TimeRange();
        tr.setDays(7);

        assertThatThrownBy(() -> converter.convert(agg(tr)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relativeWindow");
    }

    @Test
    @DisplayName("days 越界：显式拒绝")
    void daysOutOfRangeRejected() {
        assertThatThrownBy(() -> converter.convert(agg(window(0, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("天数非法");
    }

    @Test
    @DisplayName("timeRange 与 dt 过滤同时出现：时间只能声明一次，显式拒绝")
    void timeRangeAndDtFilterConflict() {
        QueryIr ir = agg(window(7, null));
        Filter f = new Filter();
        f.setField("dt");
        f.setOp(FilterOp.EQ);
        f.setValue("2026-10-19");
        ir.setFilters(new ArrayList<>(List.of(f)));

        assertThatThrownBy(() -> converter.convert(ir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能声明一次");
    }

    // ---------------- 夹具 ----------------

    /** 用生成出来的参数判断某天在不在窗口内，比读 SQL 字符串更贴近「命中/不命中」的本意 */
    private static boolean inWindow(String day, SqlCommand cmd) {
        List<Object> ps = cmd.params();
        LocalDate from = LocalDate.parse(String.valueOf(ps.get(0)));
        LocalDate anchor = LocalDate.parse(String.valueOf(ps.get(1)));
        LocalDate d = LocalDate.parse(day);
        return d.isAfter(from) && !d.isAfter(anchor);
    }

    private static QueryIr agg(TimeRange tr) {
        QueryIr ir = new QueryIr();
        ir.setSelectMode(SelectMode.AGG);
        MetricRef m = new MetricRef();
        m.setMetricId("gmv_total");
        ir.setMetrics(new ArrayList<>(List.of(m)));
        ir.setDimensions(new ArrayList<>());
        ir.setFilters(new ArrayList<>());
        ir.setOrderBy(new ArrayList<>());
        ir.setTimeRange(tr);
        return ir;
    }

    private static TimeRange window(int days, String anchor) {
        TimeRange tr = new TimeRange();
        tr.setRelativeWindow("LAST_N_DAYS");
        tr.setDays(days);
        tr.setAnchor(anchor);
        return tr;
    }
}
