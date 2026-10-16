package com.zlf.datapilot.knowledge.model;

/**
 * 字段元数据实体：对应 field_metadata 表。
 * 每个字段一行，含注释与枚举值——NL2SQL 准确率的关键。
 */
public class FieldMetadata {

    private Long id;
    private String tableName;
    private String fieldName;
    private String fieldType;
    private String fieldComment;
    /** 枚举值 JSON 字符串，如 [{"code":"1","name":"待支付"}] */
    private String enumValues;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getFieldType() {
        return fieldType;
    }

    public void setFieldType(String fieldType) {
        this.fieldType = fieldType;
    }

    public String getFieldComment() {
        return fieldComment;
    }

    public void setFieldComment(String fieldComment) {
        this.fieldComment = fieldComment;
    }

    public String getEnumValues() {
        return enumValues;
    }

    public void setEnumValues(String enumValues) {
        this.enumValues = enumValues;
    }
}
