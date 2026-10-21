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
    private String question;
    private QueryIr ir;
    private String sql;
    private List<Object> params;
    private List<String> gates;
    private List<Map<String, Object>> rows;
    private long costMs;
    private String answer;
    private String chartType;
    private String reportHtml;

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
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
}
