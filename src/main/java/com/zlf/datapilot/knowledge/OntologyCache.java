package com.zlf.datapilot.knowledge;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zlf.datapilot.event.MetadataChangedEvent;
import com.zlf.datapilot.knowledge.model.DimensionDef;
import com.zlf.datapilot.knowledge.model.FieldMetadata;
import com.zlf.datapilot.knowledge.model.GateConfig;
import com.zlf.datapilot.knowledge.model.MetricDef;
import com.zlf.datapilot.knowledge.model.SemanticModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ontology 白名单缓存：存在性/权限/语法闸门的数据源 + 代价/结果自检的阈值。
 * <p>用 Caffeine 缓存，TTL 60s；监听 Ticket 01b 的 MetadataChangedEvent 立即失效刷新，
 * 保证「PUT 一条新指标后不重启即可被闸门识别」。不做「每次请求查库」也不做「只在启动加载」。
 * 加载失败返回空快照（fail-closed）：白名单为空时存在性闸门拒绝一切，绝不漏拦。
 */
@Component
public class OntologyCache {

    private static final Logger log = LoggerFactory.getLogger(OntologyCache.class);

    private final MetadataRepository repo;
    private final Cache<String, Snapshot> cache;

    public OntologyCache(MetadataRepository repo) {
        this.repo = repo;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .build();
    }

    /** 表白名单（semantic_model 全量） */
    public Set<String> tables() {
        return snapshot().tables;
    }

    /** 字段白名单（field_metadata 去重后的纯字段名） */
    public Set<String> fields() {
        return snapshot().fields;
    }

    /** 指标白名单（metric_def 的 metric_id） */
    public Set<String> metricIds() {
        return snapshot().metricIds;
    }

    /** 指标 → 主关联表映射（存在性/权限闸门推导表用，不落库） */
    public Map<String, String> metricTables() {
        return snapshot().metricTables;
    }

    /** table.field 复合键白名单（语法闸门二次比对用） */
    public Set<String> tableFields() {
        return snapshot().tableFields;
    }

    /** 闸门阈值（gate_config），缺失时回退默认值 */
    public int gateThreshold(String paramKey, int defaultValue) {
        Integer v = snapshot().gateThresholds.get(paramKey);
        return v == null ? defaultValue : v;
    }

    /**
     * 维度字典索引：field_name → 候选表集合（Ticket 03 DETAIL 选表反查用）。
     * 同一字段可能存在于多张表（如 dt 在 4 张 ADS 表都有），所以是集合而非单值。
     */
    public Map<String, Set<String>> dimensionTables() {
        return snapshot().dimensionTables;
    }

    /** 元数据变更（PUT 指标/闸门参数）→ 立即失效缓存，下次访问重新加载 */
    @EventListener
    public void onMetadataChanged(MetadataChangedEvent event) {
        cache.invalidateAll();
        log.info("白名单缓存已刷新（{} 变更 {}）", event.entityType(), event.entityKey());
    }

    private Snapshot snapshot() {
        return cache.get("snapshot", k -> load());
    }

    private Snapshot load() {
        try {
            Set<String> tables = repo.listTables().stream()
                    .map(SemanticModel::getTableName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            List<FieldMetadata> allFields = repo.listAllFields();
            Set<String> fieldNames = allFields.stream()
                    .map(FieldMetadata::getFieldName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Set<String> tableFields = allFields.stream()
                    .filter(f -> f.getTableName() != null && f.getFieldName() != null)
                    .map(f -> f.getTableName() + "." + f.getFieldName())
                    .collect(Collectors.toSet());

            List<MetricDef> metrics = repo.listMetrics();
            Set<String> metricIds = metrics.stream()
                    .map(MetricDef::getMetricId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Map<String, String> metricTables = new HashMap<>();
            for (MetricDef m : metrics) {
                if (m.getMetricId() != null && m.getTableName() != null) {
                    metricTables.put(m.getMetricId(), m.getTableName());
                }
            }

            Map<String, Integer> gateThresholds = new HashMap<>();
            for (GateConfig g : repo.listGateConfigs()) {
                try {
                    gateThresholds.put(g.getParamKey(), Integer.parseInt(g.getParamValue()));
                } catch (NumberFormatException ignore) {
                    // 非法值已在 Ticket 01b 写接口拦掉，这里兜底忽略
                }
            }

            Map<String, Set<String>> dimensionTables = new HashMap<>();
            for (DimensionDef d : repo.listDimensions()) {
                if (d.getFieldName() != null && d.getTableName() != null) {
                    dimensionTables
                            .computeIfAbsent(d.getFieldName(), k -> new HashSet<>())
                            .add(d.getTableName());
                }
            }

            return new Snapshot(tables, fieldNames, metricIds, metricTables, tableFields,
                    gateThresholds, dimensionTables);
        } catch (DataAccessException e) {
            log.warn("白名单加载失败，返回空快照（fail-closed 拒绝一切）：{}", e.getMessage());
            return Snapshot.empty();
        }
    }

    /** 白名单快照（不可变语义，加载后不修改） */
    private record Snapshot(
            Set<String> tables,
            Set<String> fields,
            Set<String> metricIds,
            Map<String, String> metricTables,
            Set<String> tableFields,
            Map<String, Integer> gateThresholds,
            Map<String, Set<String>> dimensionTables) {

        static Snapshot empty() {
            return new Snapshot(Set.of(), Set.of(), Set.of(), Map.of(), Set.of(), Map.of(), Map.of());
        }
    }
}
