package com.zlf.datapilot.agent.gate;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.ir.DimensionRef;
import com.zlf.datapilot.ir.Filter;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.OrderBy;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.knowledge.OntologyCache;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 闸门① 存在性（IR 层）：确保 IR 里每个指标/表/字段都真实存在于 Ontology 白名单，把 LLM 幻觉率压到 0。
 * 白名单走 OntologyCache（Caffeine 缓存），O(1) 查找，不每次查库。
 * 审计 P1#12：selectMode=DETAIL（metrics 空 + dimensions≥1）不把「无指标」当幻觉拦，否则 Q4/Q5 纯 DISTINCT 题被误拦。
 */
@Component
@Order(1)
public class ExistenceGate implements Gate {

    private final OntologyCache cache;

    public ExistenceGate(OntologyCache cache) {
        this.cache = cache;
    }

    @Override
    public String name() {
        return "存在性";
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

        boolean hasMetrics = ir.getMetrics() != null && !ir.getMetrics().isEmpty();
        boolean hasDims = ir.getDimensions() != null && !ir.getDimensions().isEmpty();
        if (!hasMetrics && !hasDims) {
            return GateResult.reject("查询既无指标也无维度，超出可查询范围");
        }

        // 指标存在性 + 关联表存在性（表从 metricTables 缓存推导，不落库）
        if (hasMetrics) {
            for (MetricRef m : ir.getMetrics()) {
                String mid = lc(m.getMetricId());
                if (mid == null || !cache.metricIds().contains(mid)) {
                    return GateResult.reject("指标不存在或超出可查询范围：" + m.getMetricId());
                }
                String table = cache.metricTables().get(mid);
                if (table != null && !cache.tables().contains(table)) {
                    return GateResult.reject("指标关联的表不在白名单：" + table);
                }
                // Ticket 05：跨表占比的分母表也会被标量子查询真实读取，必须单独过白名单
                String denTable = cache.metricDenominatorTables().get(mid);
                if (denTable != null && !cache.tables().contains(denTable)) {
                    return GateResult.reject("指标 " + mid + " 的分母表不在白名单：" + denTable);
                }
            }
        }

        // 过滤字段存在性（字段名大小写不敏感，白名单真值全小写）
        if (ir.getFilters() != null) {
            for (Filter f : ir.getFilters()) {
                if (f.getField() != null && !cache.fields().contains(lc(f.getField()))) {
                    return GateResult.reject("字段不存在或超出可查询范围：" + f.getField());
                }
            }
        }

        // 维度字段存在性
        if (ir.getDimensions() != null) {
            for (DimensionRef d : ir.getDimensions()) {
                if (d.getField() != null && !cache.fields().contains(lc(d.getField()))) {
                    return GateResult.reject("维度字段不存在或超出可查询范围：" + d.getField());
                }
            }
        }

        // 排序字段存在性（field 可能是 metricId 别名，跳过）
        if (ir.getOrderBy() != null) {
            for (OrderBy o : ir.getOrderBy()) {
                String f = lc(o.getField());
                if (f == null) {
                    continue;
                }
                if (cache.metricIds().contains(f)) {
                    continue;
                }
                if (!cache.fields().contains(f)) {
                    return GateResult.reject("排序字段不存在或超出可查询范围：" + o.getField());
                }
            }
        }

        // 占比语义的分子/分母也必须是真实指标（Ticket 03 引入；计算由 05 落地）
        if (ir.getRatio() != null) {
            for (String mid : new String[]{ir.getRatio().getNumerator(), ir.getRatio().getDenominator()}) {
                if (mid == null || !cache.metricIds().contains(lc(mid))) {
                    return GateResult.reject("占比分子/分母指标不存在或超出可查询范围：" + mid);
                }
            }
        }

        return GateResult.ok();
    }

    /** 字段/指标标识符统一小写再比白名单：LLM 偶尔吐 Channel_Name，不应因此判「字段不存在」 */
    private static String lc(String s) {
        return s == null ? null : s.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
