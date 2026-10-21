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

    /** 聚合函数名：SUM(col) 中的 SUM 部分 */
    private static final String FUNC = "(SUM|COUNT|AVG|MAX|MIN)\\([a-z_][a-z0-9_]*\\)";
    /** 聚合式：SUM(total_gmv) */
    private static final Pattern AGG_PATTERN = Pattern.compile("^" + FUNC + "$");
    /** 比值式：SUM(paid_order_cnt)/SUM(total_order_cnt) */
    private static final Pattern RATIO_PATTERN = Pattern.compile("^" + FUNC + "/" + FUNC + "$");

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
        repo.saveMetric(m);
        publisher.publishEvent(new MetadataChangedEvent("metric", m.getMetricId()));
        return m;
    }

    public List<GateConfig> listGateConfigs() {
        return repo.listGateConfigs();
    }

    public GateConfig putGateConfig(GateConfig g) {
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
     * metric_sql 白名单校验：只允许聚合式 SUM(col) 或比值式 SUM(a)/SUM(b)。
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
                    "metric_sql 非法：只允许聚合式（SUM/COUNT/AVG/MAX/MIN(字段)）或比值式（聚合/聚合）");
        }
    }
}
