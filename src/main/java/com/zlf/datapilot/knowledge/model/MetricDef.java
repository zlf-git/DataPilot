package com.zlf.datapilot.knowledge.model;

/**
 * 指标口径实体：对应 metric_def 表。每个指标一行，口径只定义一次。
 * 如「支付金额」的定义 = SUM(amount) WHERE status=2。
 */
public class MetricDef {

    private Long id;
    private String metricName;
    private String enName;
    private String bizDomain;
    private String definition;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public String getEnName() {
        return enName;
    }

    public void setEnName(String enName) {
        this.enName = enName;
    }

    public String getBizDomain() {
        return bizDomain;
    }

    public void setBizDomain(String bizDomain) {
        this.bizDomain = bizDomain;
    }

    public String getDefinition() {
        return definition;
    }

    public void setDefinition(String definition) {
        this.definition = definition;
    }
}
