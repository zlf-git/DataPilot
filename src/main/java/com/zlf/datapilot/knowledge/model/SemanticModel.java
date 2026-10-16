package com.zlf.datapilot.knowledge.model;

/**
 * 语义模型实体：对应 semantic_model 表，一张业务表一行，描述「这张表是什么」。
 */
public class SemanticModel {

    private Long id;
    private String tableName;
    private String tableComment;
    private String bizDomain;

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

    public String getTableComment() {
        return tableComment;
    }

    public void setTableComment(String tableComment) {
        this.tableComment = tableComment;
    }

    public String getBizDomain() {
        return bizDomain;
    }

    public void setBizDomain(String bizDomain) {
        this.bizDomain = bizDomain;
    }
}
