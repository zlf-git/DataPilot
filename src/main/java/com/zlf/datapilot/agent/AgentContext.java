package com.zlf.datapilot.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 执行上下文：各步骤节点共享的状态载体。
 * 借鉴 DataAgent StateGraph 的「状态在节点间流转」思想。
 */
public class AgentContext {

    /** 用户原始问题 */
    private String userQuestion;

    /** 改写后的规范问题 */
    private String rewrittenQuestion;

    /** 召回的相关表名 */
    private List<String> relatedTables = new ArrayList<>();

    /** 组装好的 Prompt */
    private String prompt;

    /** 生成的 SQL */
    private String generatedSql;

    /** SQL 查询结果 */
    private List<Map<String, Object>> queryResult = new ArrayList<>();

    /** 最终给用户的自然语言解释 */
    private String answer;

    /** 建议的图表类型：bar/line/pie/table/card */
    private String chartType = "table";

    public String getUserQuestion() {
        return userQuestion;
    }

    public void setUserQuestion(String userQuestion) {
        this.userQuestion = userQuestion;
    }

    public String getRewrittenQuestion() {
        return rewrittenQuestion;
    }

    public void setRewrittenQuestion(String rewrittenQuestion) {
        this.rewrittenQuestion = rewrittenQuestion;
    }

    public List<String> getRelatedTables() {
        return relatedTables;
    }

    public void setRelatedTables(List<String> relatedTables) {
        this.relatedTables = relatedTables;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getGeneratedSql() {
        return generatedSql;
    }

    public void setGeneratedSql(String generatedSql) {
        this.generatedSql = generatedSql;
    }

    public List<Map<String, Object>> getQueryResult() {
        return queryResult;
    }

    public void setQueryResult(List<Map<String, Object>> queryResult) {
        this.queryResult = queryResult;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getChartType() {
        return chartType;
    }

    public void setChartType(String chartType) {
        this.chartType = chartType;
    }
}
