package com.zlf.datapilot.ir;

/**
 * 指标引用：指向 metric_def 里的一个指标（metric_id）。
 * 口径 SQL 由服务端按 metric_def.metric_sql 派生，不采信 LLM 直接给表达式。
 */
public class MetricRef {

    private String metricId;

    public String getMetricId() {
        return metricId;
    }

    public void setMetricId(String metricId) {
        this.metricId = metricId;
    }
}
