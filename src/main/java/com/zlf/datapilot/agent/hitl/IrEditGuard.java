package com.zlf.datapilot.agent.hitl;

import com.zlf.datapilot.ir.DimensionRef;
import com.zlf.datapilot.ir.Filter;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.OrderBy;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.ir.TimeRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 人工编辑的边界判定（Ticket 12）：拿预览快照的 IR 与客户端提交的 IR 做<b>服务端 diff</b>，
 * 判定这次编辑是「调参」还是「改结构」。
 *
 * <p><b>为什么是服务端 diff 而不是客户端声明改了哪些字段</b>：如果由客户端说「我只改了 value」，
 * 那这句自述本身就是攻击面 —— 它可以把 metrics 也改掉却仍声明只改了 value。
 * 唯一可信的做法是拿两份 IR 逐字段比对，允许清单写在服务端。
 *
 * <p>允许范围（票面钉死的四项，其余一律 {@code REPLAN}）：
 * <ul>
 *   <li>{@code filters[].value}：改成另一个值（走参数化绑定，天然不产生注入）；</li>
 *   <li>{@code limit}：改条数上限（生成器仍会套 {@code gate_config.max_rows} 兜底）；</li>
 *   <li>{@code orderBy[].direction}：asc/desc 对调；</li>
 *   <li>{@code dimensions}：换/加减维度（字段是否在白名单内由闸门①判定）。</li>
 * </ul>
 * 其余（{@code selectMode} / {@code metrics} / {@code ratio} / {@code filters} 的字段与操作符 /
 * {@code orderBy.field} / {@code timeRange}）都是结构变更 —— 它们改变的是「问的是哪个问题」，
 * 不是「这个问题的参数」。这种情况记 {@code REPLAN}：让用户回去重新问一遍，
 * 由 LLM 重新生成一份经过完整闸门的 IR，而不是把结构改动硬塞进一次预览。
 */
public final class IrEditGuard {

    private IrEditGuard() {
    }

    /**
     * diff 结果。
     *
     * @param structural 结构变更（非空即需 REPLAN）
     * @param allowed    被允许的编辑（回显给人看「系统接受了哪几处调整」）
     */
    public record Verdict(List<String> structural, List<String> allowed) {

        public boolean replan() {
            return !structural.isEmpty();
        }

        public String reason() {
            return String.join("；", structural);
        }
    }

    /**
     * 比对两份 IR。
     *
     * <p>{@code timeRange.anchor} 与 {@code compareOffsetDays} 不参与比对：两者都是服务端字段
     * （前者是解析结果、后者是环比平移），执行时会被重新解析/清零，见 {@link #enforceServerFields}。
     * 但客户端<b>显式指定</b> {@code compareOffsetDays} 要单独拦一次 —— 那等于让它自己挑窗口位置。
     */
    public static Verdict diff(QueryIr before, QueryIr after) {
        List<String> structural = new ArrayList<>();
        List<String> allowed = new ArrayList<>();

        if (before == null) {
            structural.add("预览快照缺少 IR，无法确认这次的查询还是不是原来那个");
            return new Verdict(List.copyOf(structural), List.copyOf(allowed));
        }
        if (after == null) {
            // 不传 IR = 不改，直接执行预览里的那份
            return new Verdict(List.copyOf(structural), List.copyOf(allowed));
        }

        if (!Objects.equals(before.getSelectMode(), after.getSelectMode())) {
            structural.add("查询模式变更：" + before.getSelectMode() + " → " + after.getSelectMode());
        }

        List<String> beforeMetrics = metricIds(before);
        List<String> afterMetrics = metricIds(after);
        if (!beforeMetrics.equals(afterMetrics)) {
            structural.add("指标变更：" + beforeMetrics + " → " + afterMetrics);
        }
        if (!ratioEquals(before, after)) {
            structural.add("占比语义变更：分子/分母指标不可在预览后调整");
        }

        List<String> beforeDims = dims(before);
        List<String> afterDims = dims(after);
        if (!beforeDims.equals(afterDims)) {
            allowed.add("维度变更：" + beforeDims + " → " + afterDims);
        }

        diffFilters(before.getFilters(), after.getFilters(), structural, allowed);

        diffOrderBy(before.getOrderBy(), after.getOrderBy(), structural, allowed);

        if (!Objects.equals(before.getLimit(), after.getLimit())) {
            allowed.add("limit 变更：" + before.getLimit() + " → " + after.getLimit());
        }

        diffTimeRange(before.getTimeRange(), after.getTimeRange(), structural);

        return new Verdict(List.copyOf(structural), List.copyOf(allowed));
    }

    /**
     * 把服务端专有字段钉回去：执行前必须先过这一遍，再 convert。
     *
     * <ul>
     *   <li>{@code anchor} 清空 → 生成器按<b>执行时刻</b>的数据 {@code MAX(dt)} 重新解析。
     *       客户端的 anchor 只是预览时的回显，采信它等于让窗口停在旧锚点上；</li>
     *   <li>{@code compareOffsetDays} 清空 → 环比平移只能由服务端在环比分支里填。</li>
     * </ul>
     */
    public static void enforceServerFields(QueryIr ir) {
        if (ir == null || ir.getTimeRange() == null) {
            return;
        }
        TimeRange tr = ir.getTimeRange();
        tr.setAnchor(null);
        tr.setCompareOffsetDays(null);
    }

    private static void diffFilters(List<Filter> before, List<Filter> after,
                                    List<String> structural, List<String> allowed) {
        List<Filter> b = before == null ? List.of() : before;
        List<Filter> a = after == null ? List.of() : after;
        if (b.size() != a.size()) {
            structural.add("过滤条件条数变更：" + b.size() + " → " + a.size());
            return;
        }
        for (int i = 0; i < b.size(); i++) {
            Filter x = b.get(i);
            Filter y = a.get(i);
            String pos = "第 " + (i + 1) + " 个过滤条件";
            if (x == null || y == null) {
                if (x != y) {
                    structural.add(pos + "为空的形态变更");
                }
                continue;
            }
            if (!Objects.equals(x.getField(), y.getField())) {
                structural.add(pos + "字段变更：" + x.getField() + " → " + y.getField());
            }
            if (x.getOp() != y.getOp()) {
                structural.add(pos + "操作符变更：" + x.getOp() + " → " + y.getOp());
            }
            if (!sameValue(x.getValue(), y.getValue())) {
                allowed.add(pos + "值变更：" + shortValue(x.getValue()) + " → " + shortValue(y.getValue()));
            }
        }
    }

    private static void diffOrderBy(List<OrderBy> before, List<OrderBy> after,
                                    List<String> structural, List<String> allowed) {
        List<OrderBy> b = before == null ? List.of() : before;
        List<OrderBy> a = after == null ? List.of() : after;
        if (b.size() != a.size()) {
            structural.add("排序项条数变更：" + b.size() + " → " + a.size());
            return;
        }
        for (int i = 0; i < b.size(); i++) {
            OrderBy x = b.get(i);
            OrderBy y = a.get(i);
            if (x == null || y == null) {
                if (x != y) {
                    structural.add("第 " + (i + 1) + " 个排序项为空的形态变更");
                }
                continue;
            }
            if (!Objects.equals(x.getField(), y.getField())) {
                structural.add("排序字段变更：" + x.getField() + " → " + y.getField());
            }
            if (!Objects.equals(normalizeDir(x.getDirection()), normalizeDir(y.getDirection()))) {
                allowed.add("排序方向变更：" + x.getDirection() + " → " + y.getDirection());
            }
        }
    }

    private static void diffTimeRange(TimeRange before, TimeRange after, List<String> structural) {
        if (before == null && after == null) {
            return;
        }
        if (before == null) {
            structural.add("新增时间窗口：时间范围属于查询语义，不在可编辑范围内");
            return;
        }
        if (after == null) {
            structural.add("删除时间窗口：时间范围属于查询语义，不在可编辑范围内");
            return;
        }
        if (!Objects.equals(before.getRelativeWindow(), after.getRelativeWindow())) {
            structural.add("窗口类型变更：" + before.getRelativeWindow() + " → " + after.getRelativeWindow());
        }
        if (!Objects.equals(before.getDays(), after.getDays())) {
            structural.add("窗口天数变更：" + before.getDays() + " → " + after.getDays()
                    + "（「近 7 天」和「近 30 天」是两个问题，请重新问）");
        }
        if (after.getCompareOffsetDays() != null
                && !Objects.equals(before.getCompareOffsetDays(), after.getCompareOffsetDays())) {
            structural.add("窗口平移天数由服务端控制，不接受客户端指定");
        }
    }

    private static List<String> metricIds(QueryIr ir) {
        if (ir.getMetrics() == null) {
            return List.of();
        }
        return ir.getMetrics().stream()
                .filter(Objects::nonNull)
                .map(MetricRef::getMetricId)
                .toList();
    }

    private static List<String> dims(QueryIr ir) {
        if (ir.getDimensions() == null) {
            return List.of();
        }
        return ir.getDimensions().stream()
                .filter(Objects::nonNull)
                .map(DimensionRef::getField)
                .toList();
    }

    private static boolean ratioEquals(QueryIr a, QueryIr b) {
        if (a.getRatio() == null && b.getRatio() == null) {
            return true;
        }
        if (a.getRatio() == null || b.getRatio() == null) {
            return false;
        }
        return Objects.equals(a.getRatio().getNumerator(), b.getRatio().getNumerator())
                && Objects.equals(a.getRatio().getDenominator(), b.getRatio().getDenominator());
    }

    /**
     * 值比对：JSON 往返后数字可能变类型（{@code 10} vs {@code "10"}），
     * 但过滤值最终都要落进 preparedStatement 的同一个参数位，字面量相同即视为同一处编辑。
     */
    private static boolean sameValue(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static String shortValue(Object v) {
        String s = String.valueOf(v);
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }

    private static String normalizeDir(String dir) {
        return dir == null ? "asc" : dir.trim().toLowerCase();
    }
}
