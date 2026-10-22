package com.zlf.datapilot.service;

import com.zlf.datapilot.event.MetadataChangedEvent;
import com.zlf.datapilot.knowledge.MetadataRepository;
import com.zlf.datapilot.knowledge.model.FieldMetadata;
import com.zlf.datapilot.knowledge.model.GateConfig;
import com.zlf.datapilot.knowledge.model.MetricDef;
import com.zlf.datapilot.knowledge.model.SemanticModel;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 元数据维护服务：指标 / 字段 / 闸门参数的读写。
 * 写操作带 metric_sql 白名单校验，成功后发布 MetadataChangedEvent 供闸门刷新缓存。
 */
@Service
public class MetadataService {

    /** 聚合函数名：SUM/COUNT/AVG/MAX/MIN（大小写不敏感） */
    private static final String FN = "(SUM|COUNT|AVG|MAX|MIN)";
    /** 聚合参数：字段名（字母/下划线开头）或 COUNT(*) 的 * */
    private static final String ARG = "([a-z_][a-z0-9_]*|\\*)";
    /** 聚合式：SUM(total_gmv) / COUNT(*) / SUM (total_gmv)（允许函数名与括号间空格、括号内空格） */
    private static final String AGG = FN + "\\s*\\(\\s*" + ARG + "\\s*\\)";
    private static final Pattern AGG_PATTERN = Pattern.compile("^" + AGG + "$", Pattern.CASE_INSENSITIVE);
    /** 比值式：SUM(paid_order_cnt)/SUM(total_order_cnt)（允许 / 两侧空格） */
    private static final Pattern RATIO_PATTERN = Pattern.compile("^" + AGG + "\\s*/\\s*" + AGG + "$", Pattern.CASE_INSENSITIVE);

    /** 表名格式：跨表占比的分母表来自元数据，仍过一遍格式校验 */
    private static final Pattern TABLE_NAME = Pattern.compile("^[a-z_][a-z0-9_]*$");

    /** 字段角色白名单（Ticket 13）：落库前归一化，杜绝 'Dimension'/'维度' 之类拼进白名单 */
    private static final Map<String, String> FIELD_ROLES = Map.of(
            "dimension", "dimension",
            "metric", "metric");

    /** enum_values 只做语法校验用的 ObjectMapper（不参与序列化，纯判「是不是 JSON 数组」） */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 闸门参数键 → 所属闸门的规范映射（写 gate 时按 param_key 归一化 gate_name，杜绝拼出幽灵行） */
    private static final Map<String, String> GATE_PARAM_TO_GATE = Map.of(
            "max_join", "cost",
            "max_metric", "cost",
            "max_limit", "cost",
            "max_complexity", "cost",
            "max_rows", "result");

    private final MetadataRepository repo;
    private final ApplicationEventPublisher publisher;

    public MetadataService(MetadataRepository repo, ApplicationEventPublisher publisher) {
        this.repo = repo;
        this.publisher = publisher;
    }

    public List<FieldMetadata> listFields() {
        return repo.listAllFields();
    }

    /** 表清单（Ticket 13 元数据页左侧列表；只读，表的增删不在本票范围） */
    public List<SemanticModel> listTables() {
        return repo.listTables();
    }

    public List<MetricDef> listMetrics() {
        return repo.listMetrics();
    }

    public MetricDef putMetric(MetricDef m) {
        validateMetricSql(m.getMetricSql());
        validateRatioConfig(m);
        // P0-1：撞 metric_name 唯一键（且不是同一条）直接 400，绝不允许静默改写别人的指标
        MetricDef byName = repo.findByMetricName(m.getMetricName());
        if (byName != null && !byName.getMetricId().equals(m.getMetricId())) {
            throw new IllegalArgumentException(
                    "指标中文名「" + m.getMetricName() + "」已被 metric_id=" + byName.getMetricId()
                            + " 占用，不能用于 " + m.getMetricId());
        }
        repo.saveMetric(m);
        publisher.publishEvent(new MetadataChangedEvent("metric", m.getMetricId()));
        return m;
    }

    public List<GateConfig> listGateConfigs() {
        return repo.listGateConfigs();
    }

    /**
     * 注册一张表（Ticket 19）：补上「加表零改动」的最后一块。
     *
     * <p>此前元数据只有 {@code GET /api/metadata/tables} 没有写接口，加一张表只能手工
     * {@code INSERT INTO semantic_model} —— 那不是改代码，但也不是产品能力：它绕过了
     * {@code write-enabled} 急停开关、绕过了鉴权、也不进审计。评审把这条列为「加表零改动不成立」的
     * 三号缺口，就是因为它把「架构保证」退化成了「找个人连数据库」。
     *
     * @param createOnly {@code true}（页面「新增表」）→ 已注册则 409，不覆盖注释与业务域；
     *                   {@code false}（upsert）→ 覆盖。
     */
    public SemanticModel putTable(SemanticModel m, boolean createOnly) {
        if (m == null) {
            throw new IllegalArgumentException("表定义不能为空");
        }
        validateFieldName(m.getTableName(), "table_name");
        m.setTableComment(blankToNull(m.getTableComment()));
        m.setBizDomain(blankToNull(m.getBizDomain()));

        if (createOnly && repo.findTable(m.getTableName()) != null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "表 " + m.getTableName() + " 已注册，要改注释请用编辑（新增不会覆盖已有登记）");
        }
        repo.saveTable(m);
        publisher.publishEvent(new MetadataChangedEvent("table", m.getTableName()));
        return m;
    }

    /**
     * 注销一张表（Ticket 19）。四类引用逐条查，任一存在就 400 并说清是谁在用，<b>不做级联删除</b>。
     *
     * <p>为什么级联删除比拒绝更危险：{@code metric_def.table_name} / {@code denominator_table} /
     * {@code dimension_def} / {@code metric_table} 任一处漏删，问数时看到的都是
     * 「指标关联的表不在白名单」（闸门①的报错），而真正的原因——有人把表注销了——没人说得出来。
     * 拒绝并要求先清引用，排查路径只有一条。
     *
     * <p>字段白名单也要求先摘干净：留着 {@code field_metadata} 的行而表不在了，
     * {@code OntologyCache.fields()} 会挂着一批「表不存在」的字段，
     * {@code SchemaDrift} 的 inOntologyNotInDb 也会一直指着它。
     */
    public void deleteTable(String tableName) {
        validateFieldName(tableName, "table_name");
        if (repo.findTable(tableName) == null) {
            throw new com.zlf.datapilot.exception.NotFoundException(
                    "表不存在：" + tableName + "（刷新页面看看是不是已被别人注销）");
        }

        int fields = repo.countFieldsOfTable(tableName);
        if (fields > 0) {
            throw new IllegalArgumentException(
                    "表 " + tableName + " 还有 " + fields + " 个字段在白名单里，请先在字段白名单里逐个摘掉"
                            + "（不做级联删除：一次删掉二十几行字段，出问题无法回退）");
        }
        List<String> metricRefs = repo.metricIdsUsingTable(tableName);
        if (!metricRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "表 " + tableName + " 仍是指标的主表（" + String.join(", ", metricRefs)
                            + "），请先改 metric_def.table_name");
        }
        List<String> denomRefs = repo.metricIdsUsingTableAsDenominator(tableName);
        if (!denomRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "表 " + tableName + " 仍是复合指标的分母表（" + String.join(", ", denomRefs)
                            + "），请先改 metric_def.denominator_table");
        }
        List<String> dimRefs = repo.dimensionNamesOfTable(tableName);
        if (!dimRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "表 " + tableName + " 仍被维度字典引用（维度：" + String.join(", ", dimRefs)
                            + "），请先改 dimension_def.table_name");
        }
        List<String> metricTableRefs = repo.metricTableRefs(tableName);
        if (!metricTableRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "表 " + tableName + " 仍挂在 metric_table 关联里（指标：" + String.join(", ", metricTableRefs)
                            + "），请先清理 metric_table");
        }

        repo.deleteTable(tableName);
        publisher.publishEvent(new MetadataChangedEvent("table", tableName));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * 字段白名单写入（Ticket 13）：元数据页「字段白名单编辑」的落点。
     *
     * <p>为什么校验得这么细：{@code field_metadata} 不只是展示用的注释表，它就是
     * 存在性/语法闸门的字段白名单（{@code OntologyCache.fields()/tableFields()}）。
     * 随手写错一条，后果是「闸门放行一个不存在的列」→ 一路到 JDBC 才炸；
     * 或者「表名写错」→ 白名单里挂着一张 semantic_model 里没有的表。
     * 所以三件事在写库前拦掉：表必须已注册、字段名/表名合法、角色与枚举值合法。
     */
    public FieldMetadata putField(FieldMetadata f) {
        return putField(f, false);
    }

    /**
     * 字段白名单写入，可要求「只新建、不覆盖」（QA-13 P2-2）。
     *
     * @param createOnly {@code true} 时若该 (table, field) 已存在则抛 409。
     *                   页面的「新增字段」走这条路：没有它，「新增」实际是覆盖 ——
     *                   填一个已存在的字段名不报重复，而是把原来的 field_role / field_comment /
     *                   enum_values 静默盖掉。两个人在维护白名单时，后者会无感改掉前者的配置，
     *                   而 field_role 一改，闸门对这条字段的校验口径就跟着变了。
     *                   <p>为什么必须由接口决定而不是前端查一遍：前端查重是「尽力而为」，
     *                   并发下两个人同时新增照样都成功；而且第三方调用方根本不经过页面。
     */
    public FieldMetadata putField(FieldMetadata f, boolean createOnly) {
        if (f == null) {
            throw new IllegalArgumentException("字段定义不能为空");
        }
        validateFieldName(f.getTableName(), "table_name");
        validateFieldName(f.getFieldName(), "field_name");

        // 表必须先注册（Ticket 13）：白名单是按 table.field 复合键比对的，
        // 表不在 semantic_model 里，这条字段永远匹配不上，属于「配了等于没配」的幽灵数据
        boolean tableKnown = repo.listTables().stream()
                .anyMatch(t -> f.getTableName().equals(t.getTableName()));
        if (!tableKnown) {
            throw new IllegalArgumentException(
                    "table_name「" + f.getTableName() + "」未在 semantic_model 注册，不能先加字段");
        }

        // 角色归一化：空 = 不标；非空必须落在白名单内（历史数据里有写 '维度' 的，这里明确拒绝而不是默默存）
        String role = f.getFieldRole() == null ? null : f.getFieldRole().trim().toLowerCase();
        if (role != null && !role.isEmpty() && !FIELD_ROLES.containsKey(role)) {
            throw new IllegalArgumentException(
                    "field_role 非法：" + f.getFieldRole() + "（只允许 dimension / metric / 留空）");
        }
        f.setFieldRole(role == null || role.isEmpty() ? null : role);

        // 空串 ≠ null：enum_values 在库里是 JSON 列，写 '' 会被 MySQL 判为
        // 「Invalid JSON text: The document is empty」直接 500/400。
        // 「留空 = 没有枚举」这条语义必须由写入口归一化，不能让每个调用方各自记得传 null。
        f.setEnumValues(f.getEnumValues() == null || f.getEnumValues().isBlank()
                ? null : f.getEnumValues().trim());

        validateEnumValues(f.getEnumValues());

        if (createOnly && repo.findField(f.getTableName(), f.getFieldName()) != null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "字段 " + f.getTableName() + "." + f.getFieldName()
                            + " 已在白名单里，要改请用编辑（新增不会覆盖已有配置）");
        }

        repo.saveField(f);
        publisher.publishEvent(new MetadataChangedEvent("field", f.getTableName() + "." + f.getFieldName()));
        return f;
    }

    /**
     * 把字段移出白名单（Ticket 13）。
     *
     * <p>这是本票唯一会造成「能力下降」的写操作，所以先查引用：
     * 维度字典指着它、或某个指标的可用维度里列了它 —— 直接 400 并说清是谁在用。
     * 不做级联删除：删字段顺手删掉别人维度定义，比拒绝更难排查。
     */
    public void deleteField(String tableName, String fieldName) {
        validateFieldName(tableName, "table_name");
        validateFieldName(fieldName, "field_name");
        FieldMetadata existing = repo.findField(tableName, fieldName);
        if (existing == null) {
            // 404 而不是 400（QA-13 P3-1）：东西没了和参数传错，处置方式不一样
            throw new com.zlf.datapilot.exception.NotFoundException(
                    "字段不存在：" + tableName + "." + fieldName + "（刷新页面看看是不是已被别人摘掉）");
        }
        int dimRefs = repo.countDimensionRefs(tableName, fieldName);
        if (dimRefs > 0) {
            throw new IllegalArgumentException(
                    "字段 " + tableName + "." + fieldName + " 仍被维度字典引用（" + dimRefs
                            + " 条），请先改 dimension_def 再摘白名单");
        }
        List<String> metricRefs = repo.metricIdsUsingField(fieldName);
        if (!metricRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "字段 " + fieldName + " 仍出现在指标可用维度中（" + String.join(", ", metricRefs)
                            + "），请先改 metric_def.dimensions 再摘白名单");
        }
        // 第三条守卫（QA-13 P2-1）：口径里写的列。漏了它，paid_order_cnt 这种「只被 metric_sql 引用」
        // 的字段会被摘掉，而摘掉之后行为还会分叉 —— 简单指标照样跑（物理列还在，白名单只管闸门），
        // 复合指标报「字段不属于表」。用户看到的原因完全指错了地方。
        List<String> sqlRefs = metricIdsReferencingColumn(tableName, fieldName);
        if (!sqlRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "字段 " + fieldName + " 仍被指标口径引用（" + String.join(", ", sqlRefs)
                            + "），请先改 metric_def 的 metric_sql / denominator_sql 再摘白名单");
        }
        repo.deleteField(tableName, fieldName);
        publisher.publishEvent(new MetadataChangedEvent("field", tableName + "." + fieldName));
    }

    /**
     * 哪些指标的 SQL 里真的出现了这一列（QA-13 P2-1）。
     *
     * <p><b>不能用 {@code contains} / {@code LIKE '%x%'}</b>：{@code dt} 会命中 {@code create_dt}，
     * 仓库里 {@code dimensions} 那条用 {@code FIND_IN_SET} 规避过同一个坑，这里同理。
     * 用前后字符边界精确匹配：{@code SUM(paid_order_cnt)}、{@code `paid_order_cnt`}、
     * {@code a.paid_order_cnt} 都算引用，{@code create_dt} 不算。
     *
     * <p>代价：别名 {@code AS paid_order_cnt} 也会被算成引用（多拦一次，不会漏拦）。
     * 宁可让人先去改口径，也不要放行一条「摘了之后指标静默报错」的删除。
     */
    private List<String> metricIdsReferencingColumn(String tableName, String fieldName) {
        String regex = "(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(fieldName) + "(?![A-Za-z0-9_])";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE);
        return repo.listMetricSqlRefs(tableName).stream()
                .filter(r -> mentionsColumn(p, r.metricSql()) || mentionsColumn(p, r.denominatorSql()))
                .map(MetadataRepository.MetricSqlRef::metricId)
                .distinct()
                .toList();
    }

    private static boolean mentionsColumn(java.util.regex.Pattern p, String sql) {
        return sql != null && !sql.isBlank() && p.matcher(sql).find();
    }

    private void validateFieldName(String v, String label) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
        if (!TABLE_NAME.matcher(v).matches()) {
            throw new IllegalArgumentException(label + " 非法：" + v + "（只允许小写字母/数字/下划线，字母或下划线开头）");
        }
    }

    /** enum_values 若是字符串，必须是 JSON 数组（前端建议值靠它渲染，坏 JSON 会让页面白屏而不是报错） */
    private void validateEnumValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            var node = JSON.readTree(raw);
            if (!node.isArray()) {
                throw new IllegalArgumentException("enum_values 必须是 JSON 数组，如 [\"APP\",\"小程序\"]");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("enum_values 不是合法 JSON：" + e.getOriginalMessage());
        }
    }

    public GateConfig putGateConfig(GateConfig g) {
        validateGateConfig(g);
        repo.saveGateConfig(g);
        publisher.publishEvent(new MetadataChangedEvent("gate", g.getGateName() + "." + g.getParamKey()));
        // 回显落库行而不是请求体（QA-13 P3-3）：库里 update_time 由 DB 填，
        // 直接把入参丢回去会让调用方拿到 updateTime=null，前端只能靠重新 load() 兜。
        GateConfig saved = repo.findGateConfig(g.getGateName(), g.getParamKey());
        return saved != null ? saved : g;
    }

    public Map<String, Object> metadataHealth() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("metricCount", repo.countMetrics());
        r.put("fieldCount", repo.countFields());
        r.put("gateConfigCount", repo.countGateConfigs());
        return r;
    }

    /**
     * metric_sql 白名单校验：只允许聚合式 SUM/COUNT/AVG/MAX/MIN(col)（含 COUNT(*)，大小写不敏感、
     * 允许函数名与括号间空格）或比值式（聚合/聚合）。
     * 参数化只绑 filter value、不绑指标表达式，不校验即注入。
     * 非法抛 IllegalArgumentException（Controller 转 400，且校验发生在写库之前，库内不变）。
     */
    private void validateMetricSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("metric_sql 不能为空");
        }
        String s = sql.trim();
        if (!AGG_PATTERN.matcher(s).matches() && !RATIO_PATTERN.matcher(s).matches()) {
            throw new IllegalArgumentException(
                    "metric_sql 非法：只允许聚合式（SUM/COUNT/AVG/MAX/MIN(字段) 或 COUNT(*)）或比值式（聚合/聚合）");
        }
    }

    /**
     * 复合指标口径自洽校验（Ticket 05）：写库前把「配了半边」的口径拦掉。
     *
     * <p>两种合法形态：
     * <ol>
     *   <li>同表比值：metric_type=composite 且 metric_sql 形如 {@code SUM(a)/SUM(b)}</li>
     *   <li>跨表比值：metric_sql 只写分子聚合，denominator_table + denominator_sql 一起填，
     *       且 denominator_sql 必须自带聚合（标量子查询无聚合会返回多行，直接 SQL 报错）</li>
     * </ol>
     * 只填分母表不填分母 SQL、或只填分母 SQL 不填表，都在这里 400，不留到运行时炸。
     */
    private void validateRatioConfig(MetricDef m) {
        String type = m.getMetricType() == null ? "" : m.getMetricType().trim().toLowerCase();
        String sql = m.getMetricSql().trim();
        String denTable = m.getDenominatorTable() == null ? "" : m.getDenominatorTable().trim();
        String denSql = m.getDenominatorSql() == null ? "" : m.getDenominatorSql().trim();

        if (!denTable.isEmpty()) {
            if (!TABLE_NAME.matcher(denTable).matches()) {
                throw new IllegalArgumentException("denominator_table 非法：" + denTable + "（只允许小写字母/数字/下划线）");
            }
            if (denSql.isEmpty()) {
                throw new IllegalArgumentException(
                        "denominator_table 已填但缺 denominator_sql（Ticket 05）：跨表占比必须给出分母口径，如 SUM(total_gmv)");
            }
            if (!AGG_PATTERN.matcher(denSql).matches()) {
                throw new IllegalArgumentException(
                        "denominator_sql 非法：分母必须自带聚合（如 SUM(total_gmv)），否则标量子查询会返回多行（Ticket 05）");
            }
            if (!AGG_PATTERN.matcher(sql).matches()) {
                throw new IllegalArgumentException(
                        "跨表占比的 metric_sql 只能写分子聚合（如 SUM(gmv)），分母请填 denominator_sql（Ticket 05）");
            }
            if (!type.isEmpty() && !"composite".equals(type)) {
                throw new IllegalArgumentException("配了 denominator_table 的指标 metric_type 必须是 composite（Ticket 05）");
            }
            return;
        }

        if (!denSql.isEmpty()) {
            throw new IllegalArgumentException("denominator_sql 已填但缺 denominator_table（Ticket 05）");
        }
        if ("composite".equals(type)) {
            if (!RATIO_PATTERN.matcher(sql).matches()) {
                throw new IllegalArgumentException(
                        "composite 指标口径必须是「聚合/聚合」（如 SUM(a)/SUM(b)），或配 denominator_table+denominator_sql（Ticket 05）");
            }
            return;
        }
        // 类型没标（历史数据/客户端漏传）时按写法推断，不让「比值口径标成 simple」静默入库
        if (RATIO_PATTERN.matcher(sql).matches()) {
            throw new IllegalArgumentException(
                    "metric_sql 是比值写法，metric_type 应标 composite（Ticket 05）：否则生成器不会套 ROUND，数值精度与口径都不受控");
        }
    }

    /**
     * 闸门参数校验（P0-2）：param_key 必须在白名单，param_value 必须正整数，gate_name 按 param_key 归一化。
     * 否则代价闸门读 max_limit 时 Integer.parseInt("abc") 会炸，负数等于把闸门关掉。
     */
    private void validateGateConfig(GateConfig g) {
        if (g.getParamKey() == null || !GATE_PARAM_TO_GATE.containsKey(g.getParamKey())) {
            throw new IllegalArgumentException(
                    "param_key 非法：只允许 " + GATE_PARAM_TO_GATE.keySet());
        }
        // 按 param_key 归一化 gate_name，杜绝拼出 (result, max_join) 之类幽灵行
        g.setGateName(GATE_PARAM_TO_GATE.get(g.getParamKey()));
        int v;
        try {
            v = Integer.parseInt(g.getParamValue());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("param_value 必须是正整数，实际：" + g.getParamValue());
        }
        if (v <= 0) {
            throw new IllegalArgumentException("param_value 必须是正整数，实际：" + g.getParamValue());
        }
    }
}
