package com.zlf.datapilot.sql;

import com.zlf.datapilot.ir.DimensionRef;
import com.zlf.datapilot.ir.Filter;
import com.zlf.datapilot.ir.FilterOp;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.OrderBy;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.knowledge.MetadataRepository;
import com.zlf.datapilot.knowledge.model.MetricDef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * IR → 受控 SQL 转换器。
 * 口径 SQL 从 metric_def.metric_sql 派生（不采信 LLM 给表达式），value 走参数化绑定。
 *
 * <p>Ticket 01 最简实现：单指标聚合 + 单表 + 等值过滤（EQ）。
 * selectMode/timeRange/复合指标/别名排序由 03/04/05 扩展。
 *
 * <p><b>失败契约（01c 测试报告 P2-1）</b>：IR 里出现本票尚未实现的语义
 * （明细模式 / 维度分组 / 排序 / LIMIT），一律显式抛「未实现（Ticket xx）」，
 * <b>禁止静默忽略</b>——静默丢维度会生成「全表汇总」，SQL 能跑、有行、answer 为空，
 * 看起来成功但数值全错（Q6/Q7/Q11~Q13/Q15 就是这么被静默算错的），
 * 比报错危险得多，且会把失败错误归因到口径或模板。
 */
@Component
public class IrToSqlConverter {

    /** 字段名合法格式：字母/下划线开头，仅字母数字下划线。任何含空格/等号/分号/引号的注入串都过不了这关 */
    private static final Pattern FIELD_NAME = Pattern.compile("^[a-z_][a-z0-9_]*$");

    private final MetadataRepository repo;

    public IrToSqlConverter(MetadataRepository repo) {
        this.repo = repo;
    }

    public SqlCommand convert(QueryIr ir) {
        if (ir == null) {
            throw new IllegalArgumentException("IR 为空");
        }
        // ① 未实现语义一律显式拒绝，绝不静默降级（见类注释「失败契约」）
        if (ir.getMetrics() == null || ir.getMetrics().isEmpty()) {
            throw new IllegalArgumentException(
                    "明细/DISTINCT 模式未实现（Ticket 03）：当前 IR 无指标，拒绝生成错误 SQL");
        }
        if (ir.getMetrics().size() > 1) {
            throw new IllegalArgumentException("多指标未实现（Ticket 03/05）：当前 " + ir.getMetrics().size() + " 个");
        }
        if (ir.getDimensions() != null && !ir.getDimensions().isEmpty()) {
            throw new IllegalArgumentException(
                    "维度分组未实现（Ticket 03）：IR 含维度 " + dimensionNames(ir)
                            + "，拒绝静默降级为全表汇总");
        }
        if (ir.getOrderBy() != null && !ir.getOrderBy().isEmpty()) {
            throw new IllegalArgumentException(
                    "排序未实现（Ticket 03）：IR 含 orderBy " + orderByNames(ir) + "，拒绝静默忽略");
        }
        if (ir.getLimit() != null) {
            throw new IllegalArgumentException("LIMIT 未实现（Ticket 03）：IR 含 limit=" + ir.getLimit());
        }

        // ② 正常路径：单指标 + 单表 + 等值过滤
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

    private static String dimensionNames(QueryIr ir) {
        return ir.getDimensions().stream()
                .map(DimensionRef::getField)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String orderByNames(QueryIr ir) {
        return ir.getOrderBy().stream()
                .map(OrderBy::getField)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String buildCondition(Filter f, List<Object> params) {
        String field = f.getField();
        // 列位注入兜底：field 必须是干净字段名（字母/下划线开头）。即使上游闸门被绕过，这里也拦得住
        if (field == null || !FIELD_NAME.matcher(field).matches()) {
            throw new IllegalArgumentException("非法字段名：" + field);
        }
        FilterOp op = f.getOp() == null ? FilterOp.EQ : f.getOp();
        switch (op) {
            case EQ -> {
                params.add(f.getValue());
                return field + " = ?";
            }
            case IN, LIKE, GT, GE, LT, LE ->
                throw new IllegalArgumentException("Ticket 01 仅支持 EQ 过滤，暂不支持 op=" + op);
            default -> throw new IllegalArgumentException("未知操作符: " + op);
        }
    }
}
