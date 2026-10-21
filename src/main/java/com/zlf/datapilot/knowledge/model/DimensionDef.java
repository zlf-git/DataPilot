package com.zlf.datapilot.knowledge.model;

/**
 * 维度字典实体：对应 dimension_def 表。
 * 一个业务维度（如「省份」）可能落到多张表的同名字段上（如 dt 在 4 张表都有），
 * 所以 (dim_name, field_name, table_name) 才是唯一键——同一 field 可对应多张表。
 * DETAIL 模式的选表反查靠这张表：维度字段 → 候选表集合。
 */
public class DimensionDef {

    private Long id;
    private String dimName;
    private String fieldName;
    private String tableName;
    private String dimType;
    private String enumValues;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDimName() {
        return dimName;
    }

    public void setDimName(String dimName) {
        this.dimName = dimName;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getDimType() {
        return dimType;
    }

    public void setDimType(String dimType) {
        this.dimType = dimType;
    }

    public String getEnumValues() {
        return enumValues;
    }

    public void setEnumValues(String enumValues) {
        this.enumValues = enumValues;
    }
}
