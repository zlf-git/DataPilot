package com.zlf.datapilot.sql;

import com.zlf.datapilot.ir.DimensionRef;
import com.zlf.datapilot.ir.Filter;
import com.zlf.datapilot.ir.FilterOp;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.OrderBy;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.ir.SelectMode;
import com.zlf.datapilot.knowledge.MetadataRepository;
import com.zlf.datapilot.knowledge.OntologyCache;
import com.zlf.datapilot.knowledge.model.MetricDef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * IR → 受控 SQL 转换器（Ticket 03 完整版）。
 *
 * <p>口径 SQL 一律从 metric_def.metric_sql 派生（不采信 LLM 给的表达式），value 全部参数化绑定，
 * 表名来自 metric_def / dimension_def 反查，字段名过格式校验后再落 SQL —— 只有「结构性标识符」
 * 进 SQL 文本，用户可控的值只进 ? 占位符。
 *
 * <p>两种模式：
 * <ul>
 *   <li>AGG：{@code SELECT dims..., metric_sql AS metricId FROM t [WHERE ..] [GROUP BY dims]}</li>
 *   <li>DETAIL：{@code SELECT DISTINCT dims... FROM t [WHERE ..]}，表由维度字典反查</li>
 * </ul>
 *
 * <p><b>失败契约</b>（01c 测试报告 P2-1）：IR 里出现本票尚未实现的语义
 * （占比计算 / timeRange / 多指标）或结构性冲突（DETAIL 带指标、排序字段不在结果列、
 * 维度字典反查歧义）一律显式抛「未实现（Ticket xx）」或具体原因，<b>禁止静默忽略</b>——
 * 静默丢维度会生成「全表汇总」，SQL 能跑、有行、answer 为空，数值全错却看着成功。
 */
@Component
public class IrToSqlConverter {

    /** 字段名/别名合法格式：字母或下划线开头，仅字母数字下划线。含空格/等号/分号/引号的一律过不了这关 */
    private static final Pattern FIELD_NAME = Pattern.compile("^[a-z_][a-z0-9_]*$");

    private static final int DEFAULT_MAX_ROWS = 1000;

    private final MetadataRepository repo;
    private final OntologyCache cache;

    public IrToSqlConverter(MetadataRepository repo, OntologyCache cache) {
        this.repo = repo;
        this.cache = cache;
    }

    public SqlCommand convert(QueryIr ir) {
        if (ir == null) {
            throw new IllegalArgumentException("IR 为空");
        }

        // ① 本票尚未实现的语义 → 显式拒绝，绝不降级成普通聚合
        if (ir.getRatio() != null) {
            throw new IllegalArgumentException(
                    "占比计算未实现（Ticket 05）：分子=" + ir.getRatio().getNumerator()
                            + " 分母=" + ir.getRatio().getDenominator()
                            + "。占比语义已在 IR 声明，不会降级成普通聚合");
        }
        if (ir.getMetrics() != null && ir.getMetrics().size() > 1) {
            throw new IllegalArgumentException("多指标未实现（Ticket 05）：当前 " + ir.getMetrics().size() + " 个");
        }

        SelectMode mode = resolveMode(ir);
        return mode == SelectMode.DETAIL ? buildDetail(ir) : buildAgg(ir);
    }

    /** selectMode 未声明时按 metrics 推断（有指标=AGG，无指标=DETAIL），兼容 01c 期间的旧 stub */
    private SelectMode resolveMode(QueryIr ir) {
        if (ir.getSelectMode() != null) {
            return ir.getSelectMode();
        }
        boolean hasMetrics = ir.getMetrics() != null && !ir.getMetrics().isEmpty();
        return hasMetrics ? SelectMode.AGG : SelectMode.DETAIL;
    }

    // ==================== AGG：聚合 ====================

    private SqlCommand buildAgg(QueryIr ir) {
        if (ir.getMetrics() == null || ir.getMetrics().isEmpty()) {
            throw new IllegalArgumentException("AGG 聚合模式必须指定指标（Ticket 03）");
        }
        MetricRef metricRef = ir.getMetrics().get(0);
        String metricId = metricRef.getMetricId();
        requireIdentifier(metricId, "metricId");

        MetricDef metric = repo.findByMetricId(metricId);
        if (metric == null) {
            throw new IllegalArgumentException("指标不存在: " + metricId);
        }
        String metricSql = metric.getMetricSql();
        if (metricSql == null || metricSql.isBlank()) {
            throw new IllegalArgumentException("指标 " + metricId + " 缺少 metric_sql");
        }
        String tableName = metric.getTableName();
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("指标 " + metricId + " 缺少 table_name");
        }

        List<DimensionRef> dims = safeDimensions(ir);
        List<String> selectItems = new ArrayList<>();
        for (DimensionRef d : dims) {
            selectItems.add(requireField(d.getField(), "维度"));
        }
        selectItems.add(metricSql + " AS " + metricId);

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", selectItems))
                .append(" FROM ").append(tableName);

        List<Object> params = new ArrayList<>();
        appendWhere(sql, ir, params);
        if (!dims.isEmpty()) {
            sql.append(" GROUP BY ").append(dims.stream()
                    .map(DimensionRef::getField)
                    .collect(Collectors.joining(", ")));
        }

        // 排序允许集：分组维度字段 + 本 IR 的指标别名（ORDER BY 别名是聚合查询的标准写法）
        Set<String> sortable = new LinkedHashSet<>();
        dims.forEach(d -> sortable.add(d.getField()));
        sortable.add(metricId);
        appendOrderBy(sql, ir.getOrderBy(), sortable);
        appendLimit(sql, ir);

        return SqlCommand.of(sql.toString(), params);
    }

    // ==================== DETAIL：明细 / DISTINCT ====================

    private SqlCommand buildDetail(QueryIr ir) {
        if (ir.getMetrics() != null && !ir.getMetrics().isEmpty()) {
            throw new IllegalArgumentException(
                    "DETAIL 明细模式不应带指标（Ticket 03）：请改用 AGG，或去掉指标");
        }
        List<DimensionRef> dims = safeDimensions(ir);
        if (dims.isEmpty()) {
            throw new IllegalArgumentException("DETAIL 明细模式必须指定至少一个维度（Ticket 03）");
        }

        String tableName = resolveDetailTable(ir, dims);
        List<String> columns = new ArrayList<>();
        for (DimensionRef d : dims) {
            columns.add(requireField(d.getField(), "维度"));
        }

        StringBuilder sql = new StringBuilder("SELECT DISTINCT ")
                .append(String.join(", ", columns))
                .append(" FROM ").append(tableName);

        List<Object> params = new ArrayList<>();
        appendWhere(sql, ir, params);

        // DISTINCT 语义下只能按结果列排序（按未选中的列排序在多数引擎直接报错，且语义不明）
        appendOrderBy(sql, ir.getOrderBy(), new LinkedHashSet<>(columns));
        appendLimit(sql, ir);

        return SqlCommand.of(sql.toString(), params);
    }

    /**
     * DETAIL 选表反查（审计 P1#13）：metrics 为空时没有 metric_def 指向的表，
     * 只能从 dimension_def 的 field→table 反查。多个维度/过滤字段取候选表的交集；
     * 交集为空（无共同表）或多于一张（歧义）都显式报错，绝不猜。
     */
    private String resolveDetailTable(QueryIr ir, List<DimensionRef> dims) {
        Map<String, Set<String>> dict = cache.dimensionTables();
        Set<String> knownFields = new LinkedHashSet<>();
        for (DimensionRef d : dims) {
            if (d.getField() != null && dict.containsKey(d.getField())) {
                knownFields.add(d.getField());
            }
        }
        List<Filter> filters = ir.getFilters() == null ? List.of() : ir.getFilters();
        for (Filter f : filters) {
            if (f.getField() != null && dict.containsKey(f.getField())) {
                knownFields.add(f.getField());
            }
        }
        if (knownFields.isEmpty()) {
            throw new IllegalArgumentException("无法确定目标表：维度 "
                    + dimFieldNames(dims) + " 均不在维度字典 dimension_def 中（Ticket 03）");
        }

        Set<String> candidates = null;
        for (String f : knownFields) {
            Set<String> tables = dict.get(f);
            if (candidates == null) {
                candidates = new HashSet<>(tables);
            } else {
                candidates.retainAll(tables);
            }
        }
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("无法确定目标表：维度 " + knownFields
                    + " 在维度字典中没有共同所属的表（Ticket 03）");
        }
        if (candidates.size() > 1) {
            throw new IllegalArgumentException("无法确定目标表：维度 " + knownFields
                    + " 对应多张候选表 " + candidates.stream().sorted().toList()
                    + "，请补充更具体的维度（Ticket 03）");
        }
        return candidates.iterator().next();
    }

    // ==================== 公共片段 ====================

    private void appendWhere(StringBuilder sql, QueryIr ir, List<Object> params) {
        if (ir.getFilters() == null || ir.getFilters().isEmpty()) {
            return;
        }
        List<String> conditions = new ArrayList<>();
        for (Filter f : ir.getFilters()) {
            conditions.add(buildCondition(f, params));
        }
        sql.append(" WHERE ").append(String.join(" AND ", conditions));
    }

    /** 参数分派：EQ 1 个 / IN n 个 / LIKE 包 % / GT/GE/LT/LE 各 1 个（Ticket 03 任务 7） */
    private String buildCondition(Filter f, List<Object> params) {
        String field = requireField(f.getField(), "过滤");
        FilterOp op = f.getOp() == null ? FilterOp.EQ : f.getOp();
        if (f.getValue() == null) {
            throw new IllegalArgumentException("过滤值不能为空：" + field);
        }
        switch (op) {
            case EQ -> {
                params.add(f.getValue());
                return field + " = ?";
            }
            case IN -> {
                List<Object> values = toList(f.getValue());
                if (values.isEmpty()) {
                    throw new IllegalArgumentException("IN 过滤值不能为空：" + field);
                }
                params.addAll(values);
                return field + " IN (" + String.join(", ", Collections.nCopies(values.size(), "?")) + ")";
            }
            case LIKE -> {
                params.add("%" + f.getValue() + "%");
                return field + " LIKE ?";
            }
            case GT -> {
                params.add(f.getValue());
                return field + " > ?";
            }
            case GE -> {
                params.add(f.getValue());
                return field + " >= ?";
            }
            case LT -> {
                params.add(f.getValue());
                return field + " < ?";
            }
            case LE -> {
                params.add(f.getValue());
                return field + " <= ?";
            }
            default -> throw new IllegalArgumentException("未知操作符: " + op);
        }
    }

    private void appendOrderBy(StringBuilder sql, List<OrderBy> orderBy, Set<String> sortable) {
        if (orderBy == null || orderBy.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        for (OrderBy o : orderBy) {
            String field = requireField(o.getField(), "排序");
            if (!sortable.contains(field)) {
                throw new IllegalArgumentException("排序字段 " + field + " 不在结果列 " + sortable
                        + " 内（Ticket 05：需要对应的聚合指标，如 sale_cnt_product）");
            }
            String dir = "desc".equalsIgnoreCase(o.getDirection()) ? "DESC" : "ASC";
            parts.add(field + " " + dir);
        }
        sql.append(" ORDER BY ").append(String.join(", ", parts));
    }

    /** limit 空时注入 gate_config.max_rows（Ticket 03 任务 5）；limit 是整型常量，无注入面 */
    private void appendLimit(StringBuilder sql, QueryIr ir) {
        int limit = ir.getLimit() == null
                ? cache.gateThreshold("max_rows", DEFAULT_MAX_ROWS)
                : ir.getLimit();
        if (limit <= 0) {
            limit = DEFAULT_MAX_ROWS;
        }
        sql.append(" LIMIT ").append(limit);
    }

    /** 字段名格式校验：即使上游闸门被绕过，含空格/等号/分号/引号的注入串也进不了 SQL */
    private String requireField(String field, String role) {
        requireIdentifier(field, role + "字段");
        return field;
    }

    private void requireIdentifier(String name, String role) {
        if (name == null || !FIELD_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("非法" + role + "名：" + name);
        }
    }

    private List<DimensionRef> safeDimensions(QueryIr ir) {
        return ir.getDimensions() == null ? List.of() : ir.getDimensions();
    }

    private static String dimFieldNames(List<DimensionRef> dims) {
        return dims.stream().map(DimensionRef::getField).collect(Collectors.joining(",", "[", "]"));
    }

    private static List<Object> toList(Object value) {
        if (value instanceof Collection<?> c) {
            return new ArrayList<>(c);
        }
        if (value instanceof String s) {
            List<Object> list = new ArrayList<>();
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    list.add(part.trim());
                }
            }
            return list;
        }
        if (value.getClass().isArray()) {
            Object[] arr = (Object[]) value;
            return new ArrayList<>(List.of(arr));
        }
        return List.of(value);
    }
}
