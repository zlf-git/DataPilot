package com.zlf.datapilot.knowledge.model;

/**
 * 业务术语同义词实体：对应 term_dict 表。
 * 把「黑话」映射到字段/条件，如「成交额」→ amount、「抖音」→ channel='01'。
 */
public class TermDict {

    private Long id;
    private String term;
    private String fieldRef;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public String getFieldRef() {
        return fieldRef;
    }

    public void setFieldRef(String fieldRef) {
        this.fieldRef = fieldRef;
    }
}
