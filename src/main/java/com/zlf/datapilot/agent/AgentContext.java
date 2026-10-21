package com.zlf.datapilot.agent;

import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.knowledge.model.RecalledMetadata;
import com.zlf.datapilot.sql.SqlCommand;
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

    /** 系统提示词（Ontology 白名单 + 输出约束） */
    private String systemPrompt;

    /** 用户提示词（问题 + 召回元数据） */
    private String prompt;

    /** LLM 生成的结构化 IR */
    private QueryIr ir;

    /** LLM 本次生成的输入 token 数（AgentOps 成本统计用） */
    private int promptTokens;

    /** LLM 本次生成的输出 token 数 */
    private int completionTokens;

    /** LLM 本次生成的总 token 数 */
    private int totalTokens;

    /** IR 转出的受控 SQL（参数化） */
    private SqlCommand sqlCommand;

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

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public QueryIr getIr() {
        return ir;
    }

    public void setIr(QueryIr ir) {
        this.ir = ir;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public SqlCommand getSqlCommand() {
        return sqlCommand;
    }

    public void setSqlCommand(SqlCommand sqlCommand) {
        this.sqlCommand = sqlCommand;
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
