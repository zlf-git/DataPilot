package com.zlf.datapilot.sql;

import com.zlf.datapilot.ir.Filter;
import com.zlf.datapilot.ir.FilterOp;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.knowledge.MetadataRepository;
import com.zlf.datapilot.knowledge.model.MetricDef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * IR → 受控 SQL 转换器。
 * 口径 SQL 从 metric_def.metric_sql 派生（不采信 LLM 给表达式），value 走参数化绑定。
 *
 * <p>Ticket 01 最简实现：单指标聚合 + 单表 + 等值过滤（EQ）。
 * selectMode/timeRange/复合指标/别名排序由 03/04/05 扩展。
 */
@Component
public class IrToSqlConverter {

    private final MetadataRepository repo;

    public IrToSqlConverter(MetadataRepository repo) {
        this.repo = repo;
    }

    public SqlCommand convert(QueryIr ir) {
        if (ir.getMetrics() == null || ir.getMetrics().isEmpty()) {
            throw new IllegalArgumentException("暂不支持无指标查询（DETAIL 明细模式由 Ticket 03 引入）");
        }
        if (ir.getMetrics().size() > 1) {
            throw new IllegalArgumentException("Ticket 01 仅支持单指标，当前 " + ir.getMetrics().size() + " 个");
        }

        MetricRef metricRef = ir.getMetrics().get(0);
        MetricDef metric = repo.listMetrics().stream()
                .filter(m -> m.getMetricId() != null && m.getMetricId().equals(metricRef.getMetricId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("指标不存在: " + metricRef.getMetricId()));

        String metricSql = metric.getMetricSql();
        if (metricSql == null || metricSql.isBlank()) {
            throw new IllegalArgumentException("指标 " + metricRef.getMetricId() + " 缺少 metric_sql");
        }
        String tableName = metric.getTableName();
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("指标 " + metricRef.getMetricId() + " 缺少 table_name");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(metricSql).append(" AS ").append(metricRef.getMetricId());
        sql.append(" FROM ").append(tableName);

        List<Object> params = new ArrayList<>();
        if (ir.getFilters() != null && !ir.getFilters().isEmpty()) {
            List<String> conditions = new ArrayList<>();
            for (Filter f : ir.getFilters()) {
                conditions.add(buildCondition(f, params));
            }
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }

        return SqlCommand.of(sql.toString(), params);
    }

    private String buildCondition(Filter f, List<Object> params) {
        FilterOp op = f.getOp() == null ? FilterOp.EQ : f.getOp();
        switch (op) {
            case EQ -> {
                params.add(f.getValue());
                return f.getField() + " = ?";
            }
            case IN, LIKE, GT, GE, LT, LE ->
                throw new IllegalArgumentException("Ticket 01 仅支持 EQ 过滤，暂不支持 op=" + op);
            default -> throw new IllegalArgumentException("未知操作符: " + op);
        }
    }
}
