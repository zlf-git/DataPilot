package com.zlf.datapilot.knowledge.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 召回结果聚合：② RecallStep 的输出。
 * 把「跟这个问题相关」的表 DDL、字段、指标、术语、few-shot 打包在一起，供 ③ Prompt 组装消费。
 */
public class RecalledMetadata {

    /** tableName -> DDL 文本 */
    private Map<String, String> ddlMap = new HashMap<>();

    /** 相关字段（含注释 + 枚举） */
    private List<FieldMetadata> fields = new ArrayList<>();

    /** 相关指标口径 */
    private List<MetricDef> metrics = new ArrayList<>();

    /** 相关业务术语 */
    private List<TermDict> terms = new ArrayList<>();

    /** 同业务域的 few-shot 示例 */
    private List<FewShotExample> fewShots = new ArrayList<>();

    public Map<String, String> getDdlMap() {
        return ddlMap;
    }

    public void setDdlMap(Map<String, String> ddlMap) {
        this.ddlMap = ddlMap;
    }

    public List<FieldMetadata> getFields() {
        return fields;
    }

    public void setFields(List<FieldMetadata> fields) {
        this.fields = fields;
    }

    public List<MetricDef> getMetrics() {
        return metrics;
    }

    public void setMetrics(List<MetricDef> metrics) {
        this.metrics = metrics;
    }

    public List<TermDict> getTerms() {
        return terms;
    }

    public void setTerms(List<TermDict> terms) {
        this.terms = terms;
    }

    public List<FewShotExample> getFewShots() {
        return fewShots;
    }

    public void setFewShots(List<FewShotExample> fewShots) {
        this.fewShots = fewShots;
    }
}
