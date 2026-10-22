package com.zlf.datapilot.controller;

import com.zlf.datapilot.agent.analysis.Analysis;
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

    /** 本轮是「要一份完整报告」（沿用上一轮问题），前端据此把报告面板默认展开（Ticket 22） */
    private boolean reportRequested;

    /**
     * 结果分析（Ticket 21）：{@code findings} 是「这些数说明了什么」，{@code gaps} 是
     * 「问题要求了但这次取数覆盖不到的部分」。
     *
     * <p>为什么和 {@code answer} 并存：{@code answer} 只描述结果形状（「共 N 行」），
     * 有图没结论的场景里它一个字的信息量都没有。{@code gaps} 非空也<b>不代表失败</b> ——
     * SQL 跑通了、数字是真的，只是问题的某几个分句在当前取数下无解，得明说。
     */
    private Analysis analysis;

    /**
     * 改写后的问题（Ticket 10）：多轮追问补全后的样子。
     * 只在有 sessionId 且发生补全时与 question 不同 —— 前端可以据此显示「理解成了什么」。
     */
    private String rewrittenQuestion;

    /** 环比那次查询的 SQL（Ticket 10；无环比时 null） */
    private String comparisonSql;

    /**
     * 环比那次查询的绑定参数（Ticket 10 QA 补充）：{@code [anchor-2d, anchor-d]}。
     *
     * <p>没有它，「环比窗口真的前移了」在外部不可观测 —— 本期与环比的 SQL 文本逐字相同（日期是 {@code ?}），
     * 只比文本的话「挪了」和「没挪」都能过。这正是 QA-10 P1-1 那条静默错数漏网的原因。
     */
    private List<Object> comparisonParams;

    /** 环比结论：前 N 天无数据 / 前 N 天的值 / 环比不可用的原因（Ticket 10；无环比时 null） */
    private String comparisonNote;

    /**
     * 预览 ID（Ticket 12）：{@code /api/ask/preview} 成功时返回，
     * 调用 {@code /api/ask/execute} 时原样带回。为空表示这次不是预览、也不可执行。
     */
    private String previewId;

    /**
     * 本次执行接受的编辑（Ticket 12）：如「第 1 个过滤条件值变更：APP → MP」。
     * <b>服务端算出来的</b>，不是客户端自述 —— 客户端说自己只改了 value 不算数，见 {@code IrEditGuard}。
     */
    private List<String> edits;

    /**
     * 编辑越界之处（Ticket 12）：非空即 status=REJECTED 且这次没有执行。
     * 内容形如「指标变更：[gmv_total] → [gmv_total, order_cnt]」，用来告诉用户「你改的是问题不是参数」。
     */
    private List<String> structuralChanges;

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

    public boolean isReportRequested() {
        return reportRequested;
    }

    public void setReportRequested(boolean reportRequested) {
        this.reportRequested = reportRequested;
    }

    public Analysis getAnalysis() {
        return analysis;
    }

    public void setAnalysis(Analysis analysis) {
        this.analysis = analysis;
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

    public List<Object> getComparisonParams() {
        return comparisonParams;
    }

    public void setComparisonParams(List<Object> comparisonParams) {
        this.comparisonParams = comparisonParams;
    }

    public String getComparisonNote() {
        return comparisonNote;
    }

    public void setComparisonNote(String comparisonNote) {
        this.comparisonNote = comparisonNote;
    }

    public String getPreviewId() {
        return previewId;
    }

    public void setPreviewId(String previewId) {
        this.previewId = previewId;
    }

    public List<String> getEdits() {
        return edits;
    }

    public void setEdits(List<String> edits) {
        this.edits = edits;
    }

    public List<String> getStructuralChanges() {
        return structuralChanges;
    }

    public void setStructuralChanges(List<String> structuralChanges) {
        this.structuralChanges = structuralChanges;
    }
}
