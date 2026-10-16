package com.zlf.datapilot.knowledge.model;

/**
 * few-shot 示例实体：对应 few_shot_example 表。
 * 问题-SQL 示例对（智能体知识），提升 NL2SQL 准确率。
 */
public class FewShotExample {

    private Long id;
    private String question;
    private String sql;
    private String bizDomain;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public String getBizDomain() {
        return bizDomain;
    }

    public void setBizDomain(String bizDomain) {
        this.bizDomain = bizDomain;
    }
}
