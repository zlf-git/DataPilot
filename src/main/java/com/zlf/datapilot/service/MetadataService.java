package com.zlf.datapilot.service;

import com.zlf.datapilot.event.MetadataChangedEvent;
import com.zlf.datapilot.knowledge.MetadataRepository;
import com.zlf.datapilot.knowledge.model.FieldMetadata;
import com.zlf.datapilot.knowledge.model.GateConfig;
import com.zlf.datapilot.knowledge.model.MetricDef;
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

    /** 闸门参数键 → 所属闸门的规范映射（写 gate 时按 param_key 归一化 gate_name，杜绝拼出幽灵行） */
    private static final Map<String, String> GATE_PARAM_TO_GATE = Map.of(
            "max_join", "cost",
            "max_metric", "cost",
            "max_limit", "cost",
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

    public GateConfig putGateConfig(GateConfig g) {
        validateGateConfig(g);
        repo.saveGateConfig(g);
        publisher.publishEvent(new MetadataChangedEvent("gate", g.getGateName() + "." + g.getParamKey()));
        return g;
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
