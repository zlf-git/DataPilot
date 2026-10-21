package com.zlf.datapilot.controller;

import com.zlf.datapilot.ir.QueryIr;

import java.util.List;
import java.util.Map;

/**
 * 问数响应契约（Ticket 01 定义，后续 ticket 只引用不改）。
 * traceId/question/ir/sql/params/gates/rows/costMs 主链路字段 + answer/chartType/reportHtml 占位（Ticket 11 填充）。
 */
public class AskResponse {

    private String traceId;

    /**
     * 运行结论（Ticket 11 补）：{@code SUCCESS} / {@code REJECTED}（闸门或改写明确拒绝）/
     * {@code FAILED}（跑挂了）。与 {@code agent_run.status} 同源同值。
     *
     * <p>存在理由：Ticket 11 起 {@code answer} 在成功时也有内容（解释节点写给用户看的一句话），
     * 此前「answer 非空 = 没跑成功」的隐含约定不再成立 —— 判据得有一个显式字段，
     * 而不是靠「某个字段恰好是空的」这种副作用。
     */
    private String status;
    private String question;
    private QueryIr ir;
    private String sql;
    private List<Object> params;
    private List<String> gates;
    private List<Map<String, Object>> rows;
    private long costMs;
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    private String answer;
    private String chartType;
    private String reportHtml;

    /**
     * 改写后的问题（Ticket 10）：多轮追问补全后的样子。
     * 只在有 sessionId 且发生补全时与 question 不同 —— 前端可以据此显示「理解成了什么」。
     */
    private String rewrittenQuestion;

    /** 环比那次查询的 SQL（Ticket 10；无环比时 null） */
    private String comparisonSql;

    /** 环比结论：前 N 天无数据 / 前 N 天的值（Ticket 10；无环比时 null） */
    private String comparisonNote;

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public QueryIr getIr() {
        return ir;
    }

    public void setIr(QueryIr ir) {
        this.ir = ir;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public List<Object> getParams() {
        return params;
    }

    public void setParams(List<Object> params) {
        this.params = params;
    }

    public List<String> getGates() {
        return gates;
    }

    public void setGates(List<String> gates) {
        this.gates = gates;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, Object>> rows) {
        this.rows = rows;
    }

    public long getCostMs() {
        return costMs;
    }

    public void setCostMs(long costMs) {
        this.costMs = costMs;
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

    public String getRewrittenQuestion() {
        return rewrittenQuestion;
    }

    public void setRewrittenQuestion(String rewrittenQuestion) {
        this.rewrittenQuestion = rewrittenQuestion;
    }

    public String getComparisonSql() {
        return comparisonSql;
    }

    public void setComparisonSql(String comparisonSql) {
        this.comparisonSql = comparisonSql;
    }

    public String getComparisonNote() {
        return comparisonNote;
    }

    public void setComparisonNote(String comparisonNote) {
        this.comparisonNote = comparisonNote;
    }
}
