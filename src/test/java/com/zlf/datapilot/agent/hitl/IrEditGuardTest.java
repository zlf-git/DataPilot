package com.zlf.datapilot.agent.hitl;

import com.zlf.datapilot.ir.DimensionRef;
import com.zlf.datapilot.ir.Filter;
import com.zlf.datapilot.ir.FilterOp;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.OrderBy;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.ir.RatioRef;
import com.zlf.datapilot.ir.SelectMode;
import com.zlf.datapilot.ir.TimeRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编辑边界（Ticket 12）。这一层的错都会直接变成安全问题：
 * 放宽一条 = 客户端可以把「问另一个问题」伪装成「改个参数」；
 * 收紧一条 = 用户改个值都要重新问一遍。
 */
class IrEditGuardTest {

    /** 一份和评测集里「各渠道转化率」同形的 IR：1 指标 + 1 维度 + 1 过滤 + 排序 + 窗口 */
    private static QueryIr base() {
        QueryIr ir = new QueryIr();
        ir.setSelectMode(SelectMode.AGG);
        ir.setMetrics(new ArrayList<>(List.of(metric("channel_conv_rate"))));
        ir.setDimensions(new ArrayList<>(List.of(dim("channel_name"))));
        Filter f = new Filter();
        f.setField("channel_name");
        f.setOp(FilterOp.EQ);
        f.setValue("APP");
        ir.setFilters(new ArrayList<>(List.of(f)));
        OrderBy ob = new OrderBy();
        ob.setField("channel_conv_rate");
        ob.setDirection("desc");
        ir.setOrderBy(new ArrayList<>(List.of(ob)));
        ir.setLimit(100);
        TimeRange tr = new TimeRange();
        tr.setRelativeWindow("LAST_N_DAYS");
        tr.setDays(7);
        ir.setTimeRange(tr);
        return ir;
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
    @DisplayName("不传 IR（null）= 不改：既不算结构变更，也没有任何编辑")
    void nullSubmitMeansNoEdit() {
        IrEditGuard.Verdict v = IrEditGuard.diff(base(), null);

        assertThat(v.replan()).isFalse();
        assertThat(v.allowed()).isEmpty();
    }

    @Test
    @DisplayName("原样提交：也不算编辑（不能把「没改」报成「改了一堆」）")
    void identicalIrHasNoEdits() {
        IrEditGuard.Verdict v = IrEditGuard.diff(base(), base());

        assertThat(v.replan()).isFalse();
        assertThat(v.allowed()).isEmpty();
    }

    @Test
    @DisplayName("改过滤值：允许（票面四项之一），且说明改的是第几个条件")
    void filterValueChangeIsAllowed() {
        QueryIr after = base();
        after.getFilters().get(0).setValue("MP");

        IrEditGuard.Verdict v = IrEditGuard.diff(base(), after);

        assertThat(v.replan()).isFalse();
        assertThat(v.allowed()).singleElement().asString().contains("第 1 个过滤条件值变更：APP → MP");
    }

    @Test
    @DisplayName("改 limit / 排序方向 / 维度：允许")
    void limitDirectionAndDimensionsAreAllowed() {
        QueryIr after = base();
        after.setLimit(20);
        after.getOrderBy().get(0).setDirection("asc");
        after.getDimensions().add(dim("province_name"));

        IrEditGuard.Verdict v = IrEditGuard.diff(base(), after);

        assertThat(v.replan()).isFalse();
        assertThat(v.allowed()).hasSize(3);
        assertThat(String.join("|", v.allowed()))
                .contains("limit 变更：100 → 20")
                .contains("排序方向变更：desc → asc")
                .contains("维度变更");
    }

    @Test
    @DisplayName("改指标：REPLAN（换指标就是换问题）")
    void metricChangeRequiresReplan() {
        QueryIr after = base();
        after.getMetrics().add(metric("gmv_total"));

        IrEditGuard.Verdict v = IrEditGuard.diff(base(), after);

        assertThat(v.replan()).isTrue();
        assertThat(v.reason()).contains("指标变更");
    }

    @Test
    @DisplayName("改过滤字段/操作符/条数：REPLAN（能换字段就能探测白名单边界）")
    void filterStructureChangeRequiresReplan() {
        QueryIr byField = base();
        byField.getFilters().get(0).setField("province_name");
        assertThat(IrEditGuard.diff(base(), byField).reason()).contains("字段变更");

        QueryIr byOp = base();
        byOp.getFilters().get(0).setOp(FilterOp.LIKE);
        assertThat(IrEditGuard.diff(base(), byOp).reason()).contains("操作符变更");

        QueryIr byCount = base();
        byCount.getFilters().add(byCount.getFilters().get(0));
        assertThat(IrEditGuard.diff(base(), byCount).reason()).contains("条数变更");
    }

    @Test
    @DisplayName("改窗口天数 / 排序字段 / 查询模式 / 占比语义：全部 REPLAN")
    void semanticChangesRequireReplan() {
        QueryIr days = base();
        days.getTimeRange().setDays(30);
        assertThat(IrEditGuard.diff(base(), days).reason()).contains("窗口天数变更");

        QueryIr obField = base();
        obField.getOrderBy().get(0).setField("channel_name");
        assertThat(IrEditGuard.diff(base(), obField).reason()).contains("排序字段变更");

        QueryIr mode = base();
        mode.setSelectMode(SelectMode.DETAIL);
        assertThat(IrEditGuard.diff(base(), mode).reason()).contains("查询模式变更");

        QueryIr ratio = base();
        RatioRef r = new RatioRef();
        r.setNumerator("gmv_channel");
        r.setDenominator("gmv_total");
        ratio.setRatio(r);
        assertThat(IrEditGuard.diff(base(), ratio).reason()).contains("占比语义变更");
    }

    @Test
    @DisplayName("客户端自己指定窗口平移天数：REPLAN（服务端字段，不许挑窗口位置）")
    void clientSpecifiedOffsetIsRejected() {
        QueryIr after = base();
        after.getTimeRange().setCompareOffsetDays(7);

        IrEditGuard.Verdict v = IrEditGuard.diff(base(), after);

        assertThat(v.replan()).isTrue();
        assertThat(v.reason()).contains("窗口平移天数由服务端控制");
    }

    @Test
    @DisplayName("anchor 不参与 diff：它是解析结果，由服务端在执行时重算")
    void anchorIsNotPartOfTheDiff() {
        QueryIr before = base();
        before.getTimeRange().setAnchor("2026-10-19");
        QueryIr after = base();
        after.getTimeRange().setAnchor("2020-01-01");

        assertThat(IrEditGuard.diff(before, after).replan()).isFalse();
    }

    @Test
    @DisplayName("enforceServerFields 把 anchor 与平移天数清零：执行时重新按数据解析")
    void enforceServerFieldsClearsServerOwnedValues() {
        QueryIr ir = base();
        ir.getTimeRange().setAnchor("2026-10-19");
        ir.getTimeRange().setCompareOffsetDays(7);

        IrEditGuard.enforceServerFields(ir);

        assertThat(ir.getTimeRange().getAnchor()).isNull();
        assertThat(ir.getTimeRange().getCompareOffsetDays()).isNull();
        assertThat(ir.getTimeRange().getDays()).isEqualTo(7);
    }

    @Test
    @DisplayName("缺快照 IR：REPLAN，不猜一份出来执行")
    void missingSnapshotRequiresReplan() {
        IrEditGuard.Verdict v = IrEditGuard.diff(null, base());

        assertThat(v.replan()).isTrue();
        assertThat(v.reason()).contains("预览快照缺少 IR");
    }
}
