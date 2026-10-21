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
 * 硬上限（阈值读 gate_config，可配置）：指标数 ≤ max_metric、join 表数 ≤ max_join、limit ≤ max_limit。
 * 复杂度公式补洞（D2：维度=0 恒通过）由 Ticket 15 收口，本票只做硬上限基础版。
 */
@Component
@Order(3)
public class CostGate implements Gate {

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

        if (ir.getLimit() != null && ir.getLimit() > maxLimit) {
            return GateResult.reject("limit " + ir.getLimit() + " 超过上限 " + maxLimit);
        }

        return GateResult.ok();
    }
}
