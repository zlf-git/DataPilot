package com.zlf.datapilot.knowledge.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 指标口径实体：对应 metric_def 表（schema v2）。
 * 每个指标一行，口径只定义一次，业务部门自助维护。
 * 字段对齐 schema.sql v2：metric_id / metric_name / metric_alias / department /
 * metric_sql / metric_type / table_name / dimensions / owner / status。
 * Ticket 05 增 denominatorTable / denominatorSql：跨表占比的分母（空 = 同表比值）。
 */
public class MetricDef {

    private Long id;

    @NotBlank(message = "metricId 不能为空")
    private String metricId;

    @NotBlank(message = "metricName 不能为空")
    private String metricName;

    private String metricAlias;
    private String department;

    @NotBlank(message = "metricSql 不能为空")
    private String metricSql;

    private String metricType;

    @NotBlank(message = "tableName 不能为空")
    private String tableName;

    private String dimensions;
    private String owner;
    private String status;

    /** 跨表占比的分母表（Ticket 05）：空 = 同表比值；填表名 = 用标量子查询从该表取分母 */
    private String denominatorTable;

    /** 分母口径 SQL（Ticket 05）：必须自带聚合，如 SUM(total_gmv) */
    private String denominatorSql;

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

    public String getDenominatorTable() {
        return denominatorTable;
    }

    public void setDenominatorTable(String denominatorTable) {
        this.denominatorTable = denominatorTable;
    }

    public String getDenominatorSql() {
        return denominatorSql;
    }

    public void setDenominatorSql(String denominatorSql) {
        this.denominatorSql = denominatorSql;
    }
}
