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
