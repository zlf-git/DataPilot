package com.zlf.datapilot.agent.gate;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.knowledge.OntologyCache;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 闸门③ 代价（IR 层）：预估查询复杂度，防止全表扫描/多表笛卡尔积拖垮集群。
 * 硬上限（阈值读 gate_config，可配置）：指标数 ≤ max_metric、join 表数 ≤ max_join、limit ≤ max_limit；
 * 复杂度分值（Ticket 15）：指标数 × 维度数 × join 数，三因子各自 {@code max(1,·)} 兜底。
 *
 * <p>公式为什么每个因子都要 max(1,·)：原式 指标数 × 维度数 × join 数 里，只要有一个因子是 0，
 * 整个乘积就是 0 —— 「10月19日当天总 GMV」这类最常见的单指标单表题维度=0、join=0，复杂度恒 0，
 * 任何阈值都拦不住（审计 P1#19 叫它「恒通过」）。三个 max(1,·) 后，最简查询 = 1，
 * 复杂度从此是「相对基线 1 的倍数」而不是「可能为 0 的乘积」。
 *
 * <p>拒绝规则是 {@code complexity >= max_complexity}（阈值即拒绝点，不是「允许上限」）：
 * acceptance 要求「阈值=1 时能拦住复杂度=1 的查询」，若用 {@code >} 则阈值 1 对应「允许复杂度 1」，
 * 仍拦不住最简查询 —— 那样补洞等于没补。
 */
@Component
@Order(3)
public class CostGate implements Gate {

    /**
     * 复杂度阈值默认值（gate_config 缺 max_complexity 行时的回退）。
     * 取 3 而不是 1：当前能力（单指标 max_metric=1、单 join max_join=1）下最重的一题
     * （跨表占比，2 张表）= 1×1×1 = 1，默认 3 不误杀正常题；一旦维度或表数再放开，
     * 分值自然逼近 3 触发拦截，不是「永远达不到的死配置」（QA-03 P2-5 的教训）。
     */
    static final int DEFAULT_MAX_COMPLEXITY = 3;

    private final OntologyCache cache;

    public CostGate(OntologyCache cache) {
        this.cache = cache;
    }

    @Override
    public String name() {
        return "代价";
    }

    @Override
    public Phase phase() {
        return Phase.IR;
    }

    @Override
    public GateResult check(AgentContext ctx) {
        QueryIr ir = ctx.getIr();
        if (ir == null) {
            return GateResult.reject("IR 为空");
        }

        int maxMetric = cache.gateThreshold("max_metric", 3);
        int maxJoin = cache.gateThreshold("max_join", 2);
        int maxLimit = cache.gateThreshold("max_limit", 1000);
        int maxComplexity = cache.gateThreshold("max_complexity", DEFAULT_MAX_COMPLEXITY);

        int metricCount = ir.getMetrics() == null ? 0 : ir.getMetrics().size();
        if (metricCount > maxMetric) {
            return GateResult.reject("查询过于复杂（指标数 " + metricCount + " > " + maxMetric + "），请拆分问题");
        }

        // join 表数 = IR 涉及的去重表数 - 1（基础版；单表=0 不算 join）
        // 跨表占比的分母表也计入（QA-05 P2-4）：标量子查询会真的读第二张表，只数 metrics 的 table_name
        // 会得出 joinCount=0，max_join 对复合指标形同虚设，而且闸门回显的「代价=PASS」是虚假安全感。
        Set<String> tables = new HashSet<>();
        Map<String, String> metricTables = cache.metricTables();
        Map<String, String> denominatorTables = cache.metricDenominatorTables();
        if (ir.getMetrics() != null) {
            for (MetricRef m : ir.getMetrics()) {
                String mid = m.getMetricId() == null ? null : m.getMetricId().trim().toLowerCase(Locale.ROOT);
                String t = metricTables.get(mid);
                if (t != null) {
                    tables.add(t.trim().toLowerCase(Locale.ROOT));
                }
                String den = denominatorTables.get(mid);
                if (den != null) {
                    // 同表分母（den == t）在这里自然去重，不会被误算成一次 join
                    tables.add(den.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        int joinCount = Math.max(0, tables.size() - 1);
        if (joinCount > maxJoin) {
            return GateResult.reject("查询过于复杂（join 表数 " + joinCount + " > " + maxJoin + "），请拆分问题");
        }

        // 复杂度分值（Ticket 15）：指标数 × 维度数 × join 数，每个因子下限 1。
        // 下限 1 不是「美化」：维度=0 的单指标题（Q1/Q2/Q9/Q14）与单表题（join=0）、
        // DETAIL 明细题（指标=0）都出现过「某因子为 0 → 整个乘积 0 → 阈值永不触发」，
        // 三个因子分别兜底才能保证最简查询得分 1、多一维/多一表就真的加分。
        int dimCount = ir.getDimensions() == null ? 0 : ir.getDimensions().size();
        int complexity = Math.max(1, metricCount) * Math.max(1, dimCount) * Math.max(1, joinCount);
        if (complexity >= maxComplexity) {
            return GateResult.reject("查询复杂度过高（复杂度 " + complexity + " ≥ 阈值 " + maxComplexity
                    + "：指标 " + metricCount + " × 维度 " + dimCount + " × join " + joinCount
                    + "，各因子下限 1），请拆分问题");
        }

        if (ir.getLimit() != null && ir.getLimit() > maxLimit) {
            return GateResult.reject("limit " + ir.getLimit() + " 超过上限 " + maxLimit);
        }

        return GateResult.ok();
    }
}
