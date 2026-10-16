package com.zlf.datapilot.agent;

import com.zlf.datapilot.knowledge.model.RecalledMetadata;
import com.zlf.datapilot.sql.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 执行上下文：各步骤节点共享的状态载体。
 * 借鉴 DataAgent StateGraph 的「状态在节点间流转」思想。
 */
public class AgentContext {

    /** 链路追踪 ID（一次 ask 一个，AgentEngine 入口生成） */
    private String traceId;

    /** 会话 ID（多轮对话） */
    private String sessionId;

    /** 用户原始问题 */
    private String userQuestion;

    /** 改写后的规范问题 */
    private String rewrittenQuestion;

    /** 召回的相关表名 */
    private List<String> relatedTables = new ArrayList<>();

    /** 召回的完整元数据（DDL/字段/指标/术语/few-shot） */
    private RecalledMetadata recalledMetadata = new RecalledMetadata();

    /** 组装好的 Prompt */
    private String prompt;

    /** 生成的 SQL */
    private String generatedSql;

    /** SQL 校验结果 */
    private ValidationResult validationResult;

    /** SQL 查询结果 */
    private List<Map<String, Object>> queryResult = new ArrayList<>();

    /** 最终给用户的自然语言解释 */
    private String answer;

    /** 建议的图表类型：bar/line/pie/table/card */
    private String chartType = "table";

    /** 生成的 HTML 报告（P1） */
    private String reportHtml;

    /** 失败信息（任一节点失败时） */
    private String errorMessage;

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

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

    public RecalledMetadata getRecalledMetadata() {
        return recalledMetadata;
    }

    public void setRecalledMetadata(RecalledMetadata recalledMetadata) {
        this.recalledMetadata = recalledMetadata;
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

    public ValidationResult getValidationResult() {
        return validationResult;
    }

    public void setValidationResult(ValidationResult validationResult) {
        this.validationResult = validationResult;
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

    public String getReportHtml() {
        return reportHtml;
    }

    public void setReportHtml(String reportHtml) {
        this.reportHtml = reportHtml;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
