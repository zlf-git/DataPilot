package com.zlf.datapilot.knowledge.model;

/**
 * 指标口径实体：对应 metric_def 表（schema v2）。
 * 每个指标一行，口径只定义一次，业务部门自助维护。
 * 字段对齐 schema.sql v2：metric_id / metric_name / metric_alias / department /
 * metric_sql / metric_type / table_name / dimensions / owner / status。
 */
public class MetricDef {

    private Long id;
    private String metricId;
    private String metricName;
    private String metricAlias;
    private String department;
    private String metricSql;
    private String metricType;
    private String tableName;
    private String dimensions;
    private String owner;
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMetricId() {
        return metricId;
    }

    public void setMetricId(String metricId) {
        this.metricId = metricId;
    }

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public String getMetricAlias() {
        return metricAlias;
    }

    public void setMetricAlias(String metricAlias) {
        this.metricAlias = metricAlias;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getMetricSql() {
        return metricSql;
    }

    public void setMetricSql(String metricSql) {
        this.metricSql = metricSql;
    }

    public String getMetricType() {
        return metricType;
    }

    public void setMetricType(String metricType) {
        this.metricType = metricType;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getDimensions() {
        return dimensions;
    }

    public void setDimensions(String dimensions) {
        this.dimensions = dimensions;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
