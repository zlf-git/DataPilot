package com.zlf.datapilot.support;

import com.zlf.datapilot.knowledge.OntologyCache;
import com.zlf.datapilot.knowledge.model.MetricDef;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 单元测试用的 Ontology 白名单替身（Ticket 06c）。
 *
 * <p>为什么 mock 而不是连库：单测要能在没有 MySQL/StarRocks 的机器上跑（CI/评审机），
 * 断言的是生成器/闸门的逻辑，不是数据库行为。白名单内容与 {@code deploy/seed.sql} 的
 * 四张 ADS 表一致，字段清单取自 {@code src/main/resources/schema.sql}，避免测试用假字段。
 */
public final class TestOntology {

    public static final String OVERVIEW = "ads_trade_overview_di";
    public static final String CHANNEL = "ads_channel_conversion_di";
    public static final String REGION = "ads_region_gmv_di";
    public static final String PRODUCT = "ads_product_sales_rank_di";

    public static final String ANCHOR = "2026-10-19";

    private TestOntology() {
    }

    /** 表 → 字段（与生产 schema 一致，测试里用假字段等于自证不存在的东西） */
    public static Map<String, List<String>> tableFields() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(OVERVIEW, List.of("dt", "total_order_cnt", "paid_order_cnt", "total_gmv", "paid_user_cnt"));
        m.put(CHANNEL, List.of("dt", "channel", "channel_name", "visit_uv", "order_cnt", "paid_order_cnt",
                "gmv", "order_conversion_rate", "pay_conversion_rate"));
        m.put(REGION, List.of("dt", "province", "province_name", "region", "order_cnt", "paid_order_cnt",
                "paid_user_cnt", "gmv"));
        m.put(PRODUCT, List.of("dt", "product_id", "product_name", "category_name", "sale_cnt", "sale_amount"));
        return m;
    }

    /** 指标定义（与 seed.sql 的 4 个代表指标一致：2 个 simple + 1 个同表复合 + 1 个跨表复合） */
    public static Map<String, MetricDef> metrics() {
        Map<String, MetricDef> m = new HashMap<>();
        m.put("gmv_total", metric("gmv_total", "SUM(total_gmv)", "simple", OVERVIEW, null, null));
        m.put("gmv_channel", metric("gmv_channel", "SUM(gmv)", "simple", CHANNEL, null, null));
        m.put("pay_conversion_rate", metric("pay_conversion_rate",
                "SUM(paid_order_cnt)/SUM(visit_uv)", "composite", CHANNEL, null, null));
        m.put("gmv_channel_ratio", metric("gmv_channel_ratio",
                "SUM(gmv)", "composite", CHANNEL, OVERVIEW, "SUM(total_gmv)"));
        m.put("sale_cnt_product", metric("sale_cnt_product", "SUM(sale_cnt)", "simple", PRODUCT, null, null));
        return m;
    }

    public static MetricDef metric(String id, String sql, String type, String table,
                                   String denTable, String denSql) {
        MetricDef d = new MetricDef();
        d.setMetricId(id);
        d.setMetricSql(sql);
        d.setMetricType(type);
        d.setTableName(table);
        d.setDenominatorTable(denTable);
        d.setDenominatorSql(denSql);
        return d;
    }

    /** 白名单缓存替身：表/字段/指标/table.field/阈值都按真实 seed 填 */
    public static OntologyCache cache() {
        OntologyCache cache = mock(OntologyCache.class);
        Map<String, List<String>> tf = tableFields();
        Map<String, MetricDef> metrics = metrics();

        Set<String> tables = new LinkedHashSet<>(tf.keySet());
        Set<String> fields = new LinkedHashSet<>();
        Set<String> tableFields = new LinkedHashSet<>();
        tf.forEach((t, cols) -> cols.forEach(c -> {
            fields.add(c);
            tableFields.add(t + "." + c);
        }));
        Map<String, String> metricTables = new HashMap<>();
        Map<String, String> denTables = new HashMap<>();
        metrics.forEach((id, d) -> {
            metricTables.put(id, d.getTableName());
            if (d.getDenominatorTable() != null) {
                denTables.put(id, d.getDenominatorTable());
            }
        });
        Map<String, Set<String>> dimTables = new HashMap<>();
        tf.forEach((t, cols) -> cols.forEach(c -> dimTables.computeIfAbsent(c, k -> new LinkedHashSet<>()).add(t)));

        Map<String, Integer> thresholds = Map.of("max_metric", 1, "max_join", 1, "max_limit", 1000, "max_rows", 1000);

        when(cache.tables()).thenReturn(tables);
        when(cache.fields()).thenReturn(fields);
        when(cache.metricIds()).thenReturn(metrics.keySet());
        when(cache.metricTables()).thenReturn(metricTables);
        when(cache.metricDenominatorTables()).thenReturn(denTables);
        when(cache.tableFields()).thenReturn(tableFields);
        when(cache.dimensionTables()).thenReturn(dimTables);
        when(cache.gateThreshold(anyString(), anyInt()))
                .thenAnswer(inv -> thresholds.getOrDefault(inv.<String>getArgument(0), inv.getArgument(1)));
        return cache;
    }
}
