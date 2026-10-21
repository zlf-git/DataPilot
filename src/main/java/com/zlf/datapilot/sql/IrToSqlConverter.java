package com.zlf.datapilot.sql;

import com.zlf.datapilot.ir.DimensionRef;
import com.zlf.datapilot.ir.Filter;
import com.zlf.datapilot.ir.FilterOp;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.OrderBy;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.ir.SelectMode;
import com.zlf.datapilot.ir.TimeRange;
import com.zlf.datapilot.knowledge.MetadataRepository;
import com.zlf.datapilot.knowledge.OntologyCache;
import com.zlf.datapilot.knowledge.model.MetricDef;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * IR → 受控 SQL 转换器（Ticket 03 完整版 + Ticket 04 时间窗口 + Ticket 05 复合指标）。
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
 * <p>复合指标（Ticket 05，metric_type=composite）统一输出 {@code ROUND(分子/分母, 4)} 单列：
 * <ul>
 *   <li>同表比值：metric_sql 写成 {@code A/B}，如 {@code SUM(paid_order_cnt)/SUM(visit_uv)}</li>
 *   <li>跨表比值：metric_sql 只写分子，分母走 {@code denominator_table + denominator_sql}，
 *       生成标量子查询 {@code (SELECT SUM(total_gmv) FROM den WHERE dt = ?)}，<b>不生成 JOIN</b>；
 *       分母 SQL 必须自带聚合，否则标量子查询返回多行直接报错</li>
 * </ul>
 * 分母一律套 {@code NULLIF(...,0)}：分母为 0 时得 NULL（占比未知），而不是除零异常把整条链路打断。
 *
 * <p><b>失败契约</b>（01c 测试报告 P2-1）：IR 里出现本票尚未实现的语义
 * （多指标）或结构性冲突（DETAIL 带指标、排序字段不在结果列、维度字典反查歧义、
 * 时间窗口与 dt 过滤重复声明、维度/过滤字段不属于指标表、跨表占比叠加非时间过滤）
 * 一律显式抛具体原因，<b>禁止静默忽略</b>——静默丢维度会生成「全表汇总」，
 * SQL 能跑、有行、answer 为空，数值全错却看着成功。
 *
 * <p>时间窗口（Ticket 04）：{@code timeRange} 展开成<b>半开区间</b> {@code dt > ? AND dt <= ?}，
 * 参数为 {@code anchor-days} 与 {@code anchor}，anchor 取数据 {@code MAX(dt)}；全文禁 BETWEEN
 * （BETWEEN 闭区间会把第 8 天也算进来）。
 */
@Component
public class IrToSqlConverter {

    /** 字段名/别名合法格式：字母或下划线开头，仅字母数字下划线。含空格/等号/分号/引号的一律过不了这关 */
    private static final Pattern FIELD_NAME = Pattern.compile("^[a-z_][a-z0-9_]*$");

    /** 表名合法格式（分母表来自元数据，仍过一遍格式校验，杜绝把 SQL 片段塞进表名） */
    private static final Pattern TABLE_NAME = Pattern.compile("^[a-z_][a-z0-9_]*$");

    /** 单个聚合口径：SUM(col) / COUNT(*) / AVG(col)…，用于从 metric_sql 里抠出参与字段做表归属校验 */
    private static final Pattern AGG_ARG = Pattern.compile(
            "^(SUM|COUNT|AVG|MAX|MIN)\\s*\\(\\s*([a-z_][a-z0-9_]*|\\*)\\s*\\)$", Pattern.CASE_INSENSITIVE);

    private static final int DEFAULT_MAX_ROWS = 1000;

    /** 时间字段（窗口锚定的列） */
    private static final String DT_FIELD = "dt";

    /** 支持的时间窗口类型：统一走「近 N 天」 */
    private static final Set<String> SUPPORTED_WINDOWS = Set.of("LAST_N_DAYS", "LAST_DAYS", "RECENT_DAYS");

    /** 窗口天数上限（超过一年视为误填） */
    private static final int MAX_WINDOW_DAYS = 366;

    private final MetadataRepository repo;
    private final OntologyCache cache;
    private final TimeAnchorProvider anchorProvider;

    public IrToSqlConverter(MetadataRepository repo, OntologyCache cache, TimeAnchorProvider anchorProvider) {
        this.repo = repo;
        this.cache = cache;
        this.anchorProvider = anchorProvider;
    }

    public SqlCommand convert(QueryIr ir) {
        if (ir == null) {
            throw new IllegalArgumentException("IR 为空");
        }

        // ① 本票尚未实现的语义 → 显式拒绝，绝不降级成普通聚合
        if (ir.getRatio() != null) {
            throw new IllegalArgumentException(
                    "占比语义请改用复合指标（Ticket 05）：IR 的 ratio{numerator=" + ir.getRatio().getNumerator()
                            + ", denominator=" + ir.getRatio().getDenominator()
                            + "} 不再直接消费，跨表占比已落成 metric_type=composite 的指标，"
                            + "同一语义不允许存在两套执行路径");
        }
        if (ir.getMetrics() != null && ir.getMetrics().size() > 1) {
            throw new IllegalArgumentException(
                    "多指标暂不支持（Ticket 05 只做单指标的复合计算）：当前 " + ir.getMetrics().size() + " 个");
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
        // QA-03 P1-2：维度/过滤字段必须属于指标表。此前只校验「字段存在于白名单」，
        // 跨表维度（gmv_total + channel_name）五闸门全 PASS，最后 DB 才报错并把异常原文漏给用户。
        assertFieldsOnTable(tableName, dims.stream().map(DimensionRef::getField).toList(), "维度");
        assertFieldsOnTable(tableName, filterFields(ir), "过滤");

        // 时间窗口只解一次（anchor 取主表 MAX(dt)），分母子查询复用同一组条件与参数
        TimeClause time = resolveTimeClause(ir, tableName);

        List<Object> params = new ArrayList<>();
        List<String> selectItems = new ArrayList<>();
        for (DimensionRef d : dims) {
            selectItems.add(requireField(d.getField(), "维度"));
        }
        String metricExpr = isComposite(metric)
                ? buildCompositeExpr(metric, ir, tableName, time, params)
                : metricSql;
        selectItems.add(metricExpr + " AS " + metricId);

        WhereClause where = buildWhere(ir, time);
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", selectItems))
                .append(" FROM ").append(tableName)
                .append(where.render());
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

        // 分母参数已在 buildCompositeExpr 内先压入（子查询文本在 WHERE 之前），主查询参数随后
        params.addAll(where.params());
        return SqlCommand.of(sql.toString(), params);
    }

    // ==================== 复合指标（Ticket 05） ====================

    /** composite 判定：类型标注、或配了分母 SQL、或口径本身就是 A/B 写法（容忍历史数据漏标类型） */
    private boolean isComposite(MetricDef metric) {
        if (notBlank(metric.getDenominatorSql())) {
            return true;
        }
        if ("composite".equalsIgnoreCase(trim(metric.getMetricType()))) {
            return true;
        }
        return metric.getMetricSql() != null && metric.getMetricSql().contains("/");
    }

    /**
     * 复合指标表达式：{@code ROUND(分子 / NULLIF(分母, 0), 4)}，只产一列，中间列在 SQL 内消掉。
     *
     * <p>跨表（denominator_table 非空）用标量子查询取分母而不是 JOIN：
     * Q15 是「渠道 GMV（渠道表）/ 当天总 GMV（总览表）」，JOIN 写法要求两张表按 dt 对齐、
     * 分母侧还可能被 JOIN 放大行数；标量子查询是标量常量，天然不会改变分组粒度。
     * 两种写法结果等价，评测只比结果不比 SQL 文本。
     */
    private String buildCompositeExpr(MetricDef metric, QueryIr ir, String mainTable,
                                      TimeClause time, List<Object> params) {
        String num = trim(metric.getMetricSql());
        String denSql = trim(metric.getDenominatorSql());
        String denTable = trim(metric.getDenominatorTable());

        // ① 同表比值：metric_sql = A/B
        if (denSql.isEmpty()) {
            if (!denTable.isEmpty()) {
                throw new IllegalArgumentException("指标 " + metric.getMetricId()
                        + " 配了 denominator_table 但缺 denominator_sql（Ticket 05）：跨表占比必须给出分母口径");
            }
            String[] parts = splitRatio(num, metric.getMetricId());
            assertAggFieldOnTable(mainTable, parts[0], "占比分子");
            assertAggFieldOnTable(mainTable, parts[1], "占比分母");
            return "ROUND(" + parts[0] + " / NULLIF(" + parts[1] + ", 0), 4)";
        }

        // ② 跨表比值：metric_sql 只写分子，分母走标量子查询
        if (denTable.isEmpty()) {
            throw new IllegalArgumentException("指标 " + metric.getMetricId()
                    + " 配了 denominator_sql 但缺 denominator_table（Ticket 05）：请写明分母所在表");
        }
        if (!TABLE_NAME.matcher(denTable).matches()) {
            throw new IllegalArgumentException("非法分母表名：" + denTable);
        }
        if (num.contains("/")) {
            throw new IllegalArgumentException("跨表占比的 metric_sql 只能写分子（Ticket 05）：实际 " + num
                    + "；分母请填 denominator_sql");
        }
        assertAggFieldOnTable(mainTable, num, "占比分子");
        assertAggFieldOnTable(denTable, denSql, "占比分母");

        String sub = buildDenominatorSelect(metric.getMetricId(), denTable, denSql, ir, time, params);
        return "ROUND(" + num + " / NULLIF((" + sub + "), 0), 4)";
    }

    /**
     * 分母标量子查询：{@code SELECT den_sql FROM den_table [WHERE 时间条件]}。
     *
     * <p>分母<b>只继承时间条件</b>（timeRange 或 dt 过滤），不继承其他过滤：
     * 「10月19日小程序渠道 GMV 占当天总 GMV 比例」的分母是<b>当天总 GMV</b>，
     * 把 channel_name 过滤推到分母上会变成「小程序占小程序」恒等于 1。
     * 由于「其他过滤该不该作用于分母」本身没有唯一答案，这里不猜也不静默丢：
     * 出现非时间过滤且指标跨表时直接显式拒绝，要求改用单表指标或拆成两次查询。
     *
     * <p>参数顺序（审计 P1#14）：分母的 ? 在主查询 WHERE 的 ? 之前（文本上子查询先出现），
     * 所以这里先把分母参数压进 params，主查询参数由 buildAgg 之后追加。
     */
    private String buildDenominatorSelect(String metricId, String denTable, String denSql,
                                          QueryIr ir, TimeClause time, List<Object> params) {
        List<Filter> filters = ir.getFilters() == null ? List.of() : ir.getFilters();
        List<String> nonTime = filters.stream()
                .map(Filter::getField)
                .filter(f -> f != null && !DT_FIELD.equalsIgnoreCase(f))
                .toList();
        if (!nonTime.isEmpty()) {
            throw new IllegalArgumentException("跨表占比不支持叠加非时间过滤（Ticket 05）：指标 " + metricId
                    + " 的分母是否也要按 " + nonTime + " 过滤没有唯一口径"
                    + "（推过去会变成「自己占自己」），请改用单表指标或拆成两次查询");
        }

        List<String> conds = new ArrayList<>();
        List<Object> denParams = new ArrayList<>(time.params());
        conds.addAll(time.conds());
        if (time.isEmpty()) {
            // 没有 timeRange 时，分母复用 dt 等值过滤（其它 op 原样保留，都是纯时间条件）
            for (Filter f : filters) {
                if (f.getField() != null && DT_FIELD.equalsIgnoreCase(f.getField())) {
                    conds.add(buildCondition(f, denParams));
                }
            }
        }
        params.addAll(denParams);

        StringBuilder sub = new StringBuilder("SELECT ").append(denSql).append(" FROM ").append(denTable);
        if (!conds.isEmpty()) {
            sub.append(" WHERE ").append(String.join(" AND ", conds));
        }
        return sub.toString();
    }

    /** 把 A/B 拆成两段（顶层 /，口径里的 / 只出现在比值位）；段数不为 2 说明口径写错，显式拒绝 */
    private String[] splitRatio(String metricSql, String metricId) {
        String[] parts = metricSql.split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("指标 " + metricId
                    + " 的 composite 口径必须是「聚合/聚合」（Ticket 05）：实际 " + metricSql);
        }
        return new String[]{braces(parts[0].trim()), braces(parts[1].trim())};
    }

    /** 去掉包裹的括号：SUM(x) 与 (SUM(x)) 等价，防止元数据里多写一层括号导致后续判断走偏 */
    private String braces(String s) {
        String out = s;
        while (out.startsWith("(") && out.endsWith(")") && out.length() > 2) {
            out = out.substring(1, out.length() - 1).trim();
        }
        return out;
    }

    /** 聚合口径里的字段必须属于给定表：语法闸门只比全局字段名，管不了「这个字段是不是这张表的」 */
    private void assertAggFieldOnTable(String table, String aggSql, String role) {
        Matcher m = AGG_ARG.matcher(aggSql);
        if (!m.matches()) {
            throw new IllegalArgumentException(role + "口径非法：" + aggSql
                    + "（只允许 SUM/COUNT/AVG/MAX/MIN(字段) 或 COUNT(*)，Ticket 05）");
        }
        String field = m.group(2);
        if ("*".equals(field)) {
            return;
        }
        if (!cache.tableFields().contains(table + "." + field)) {
            throw new IllegalArgumentException(role + "字段 " + field + " 不属于表 " + table
                    + "（Ontology 字段白名单按表隔离）");
        }
    }

    /** 维度/过滤字段的表归属校验（QA-03 P1-2） */
    private void assertFieldsOnTable(String table, Collection<String> fields, String role) {
        for (String f : fields) {
            if (f == null) {
                continue;
            }
            if (!cache.tableFields().contains(table + "." + f)) {
                throw new IllegalArgumentException(role + "字段 " + f + " 不属于表 " + table
                        + "（Ontology 字段白名单按表隔离，跨表维度请拆成两次查询）");
            }
        }
    }

    private List<String> filterFields(QueryIr ir) {
        return ir.getFilters() == null ? List.of()
                : ir.getFilters().stream().map(Filter::getField).filter(f -> f != null).toList();
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
        assertFieldsOnTable(tableName, dims.stream().map(DimensionRef::getField).toList(), "维度");
        assertFieldsOnTable(tableName, filterFields(ir), "过滤");

        List<String> columns = new ArrayList<>();
        for (DimensionRef d : dims) {
            columns.add(requireField(d.getField(), "维度"));
        }

        WhereClause where = buildWhere(ir, resolveTimeClause(ir, tableName));
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ")
                .append(String.join(", ", columns))
                .append(" FROM ").append(tableName)
                .append(where.render());

        // DISTINCT 语义下只能按结果列排序（按未选中的列排序在多数引擎直接报错，且语义不明）
        appendOrderBy(sql, ir.getOrderBy(), new LinkedHashSet<>(columns));
        appendLimit(sql, ir);

        return SqlCommand.of(sql.toString(), where.params());
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

    /** WHERE 子句 = 过滤条件 + 时间条件（顺序固定：先过滤后时间，参数按同序压入） */
    private record WhereClause(List<String> conditions, List<Object> params) {

        String render() {
            return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        }
    }

    /** 时间窗口解析结果（Ticket 04）：条件文本 + 参数，留给主查询与分母子查询共用 */
    private record TimeClause(List<String> conds, List<Object> params) {

        static TimeClause empty() {
            return new TimeClause(List.of(), List.of());
        }

        boolean isEmpty() {
            return conds.isEmpty();
        }
    }

    private WhereClause buildWhere(QueryIr ir, TimeClause time) {
        List<Filter> filters = ir.getFilters() == null ? List.of() : ir.getFilters();
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (Filter f : filters) {
            conditions.add(buildCondition(f, params));
        }
        conditions.addAll(time.conds());
        params.addAll(time.params());
        return new WhereClause(conditions, params);
    }

    /**
     * 解析时间窗口：同一层时间约束只能声明一次（timeRange 与 dt 过滤并存会让「近 7 天」与「某天」互相打架）。
     * 只解一次并复用，避免分母表 anchor 与主表不一致时误报「锚点不一致」。
     */
    private TimeClause resolveTimeClause(QueryIr ir, String table) {
        List<Filter> filters = ir.getFilters() == null ? List.of() : ir.getFilters();
        boolean hasDtFilter = filters.stream()
                .anyMatch(f -> f.getField() != null && DT_FIELD.equalsIgnoreCase(f.getField()));
        if (ir.getTimeRange() != null && hasDtFilter) {
            throw new IllegalArgumentException(
                    "timeRange 与 " + DT_FIELD + " 过滤条件同时存在（Ticket 04）：时间范围只能声明一次，请只用其中一种");
        }
        if (ir.getTimeRange() == null) {
            return TimeClause.empty();
        }
        List<Object> params = new ArrayList<>();
        List<String> conds = buildTimeRange(ir.getTimeRange(), table, params);
        return new TimeClause(conds, params);
    }

    /**
     * 时间窗口展开（Ticket 04 任务 3）：{@code dt > ? AND dt <= ?}，参数 {@code anchor-days} / {@code anchor}。
     *
     * <p>为什么不用 BETWEEN：BETWEEN 是闭区间，{@code BETWEEN '10-12' AND '10-19'} 会取到
     * 10-12~10-19 共 <b>8</b> 天，与「近 7 天」不符（审计 P0#1）。半开下界 + 闭合上界才是 7 天。
     */
    private List<String> buildTimeRange(TimeRange tr, String table, List<Object> params) {
        String window = tr.getRelativeWindow() == null ? "LAST_N_DAYS" : tr.getRelativeWindow().trim().toUpperCase();
        if (!SUPPORTED_WINDOWS.contains(window)) {
            throw new IllegalArgumentException("不支持的时间窗口类型：" + tr.getRelativeWindow()
                    + "（Ticket 04 仅支持 LAST_N_DAYS，窗口由数据最新日往前推）");
        }
        Integer days = tr.getDays();
        if (days == null) {
            throw new IllegalArgumentException("时间窗口缺少 days（Ticket 04）：「近 N 天」必须给出 N");
        }
        if (days < 1 || days > MAX_WINDOW_DAYS) {
            throw new IllegalArgumentException("时间窗口天数非法：" + days + "（允许 1~" + MAX_WINDOW_DAYS + "）");
        }

        // 锚点取数据 MAX(dt)，不取 now()：数据滞后时 now() 会把窗口整体前移、最后一天变空分区
        String anchorStr = anchorProvider.anchor(table);
        LocalDate anchor = LocalDate.parse(anchorStr);
        if (tr.getAnchor() != null && !tr.getAnchor().isBlank() && !anchorStr.equals(tr.getAnchor().trim())) {
            throw new IllegalArgumentException("时间锚点与数据最新日期不一致（Ticket 04）：IR 给的 anchor="
                    + tr.getAnchor() + "，数据 MAX(" + DT_FIELD + ")=" + anchorStr);
        }
        // 回填解析结果，便于响应体回显「实际用的窗口」
        tr.setAnchor(anchorStr);

        LocalDate from = anchor.minusDays(days);
        params.add(from.toString());
        params.add(anchorStr);
        return List.of(DT_FIELD + " > ?", DT_FIELD + " <= ?");
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

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
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
