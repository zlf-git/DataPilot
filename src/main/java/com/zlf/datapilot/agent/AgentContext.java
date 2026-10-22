package com.zlf.datapilot.agent;

import com.zlf.datapilot.agent.analysis.Analysis;
import com.zlf.datapilot.agent.trace.RunStatus;
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

    /**
     * 结果分析（Ticket 21）：结论 + 缺口。{@code answer} 是「一句话摘要」，
     * 这里是「几句结论」，两者不互相替代 —— 摘要说「共 N 行」，分析说「谁占多少、谁比均值高」。
     *
     * <p><b>写入方唯一</b>：{@code ExplainStep}。初值 {@link Analysis#EMPTY} 而非 null，
     * 前端据此判断「这次没有分析可渲染」，不用 null 检查。
     */
    private Analysis analysis = Analysis.EMPTY;

    /**
     * 建议的图表类型：metric/bar/line/table。<b>初值 null</b>（QA-11 P2-2）。
     *
     * <p>以前初值是 {@code "table"}，于是失败/拒绝的响应也会带着 {@code chartType=table} 而
     * {@code reportHtml=null} —— 前端按 chartType 决定渲染就会拿到一张空图，
     * 而它真正该表达的是「这次没有图表」。null 才表示「没有」，有值才表示「有，且是这个形状」。
     */
    private String chartType;

    /** 生成的 HTML 报告（P1） */
    private String reportHtml;

    /**
     * 本轮是「要一份完整报告」而不是一次新提问（Ticket 22）。
     *
     * <p>来源：{@code RewriteStep} 识别出纯报告请求（「我需要完整的报告」）后，
     * 沿用上一轮问题并置 true —— 语义确定（不新增取数条件），只是要求换个呈现方式。
     * 前端据此把报告面板默认展开，避免用户再点一次。
     */
    private boolean reportRequested;

    /** 失败信息（任一节点失败时） */
    private String errorMessage;

    /**
     * 谁拒绝了这次查询（Ticket 10/11 的 QA 收口）：{@code rewrite} / {@code identify} / {@code converter}。
     * 非 null 表示「策略生效、不是故障」，{@link RunStatus#of} 据此落 REJECTED。
     *
     * <p><b>只能通过 {@link #reject(String, String)} 写</b>，不提供裸 setter —— 见该方法注释。
     */
    private String rejectedBy;

    /** 闸门执行结果（链路透明回显，GateChain 按序追加） */
    private List<String> gateResults = new ArrayList<>();

    /**
     * 环比天数（Ticket 10）：非 null 表示本轮是多轮里的「那环比呢」，
     * 引擎会在本期结果之外，把同一份 IR 的窗口往前挪 N 天再跑一次。
     */
    private Integer comparisonDays;

    /** 环比那次查询的 SQL（回显用；不含本期 SQL） */
    private String comparisonSql;

    /** 环比那次查询的结果（为空表示前 N 天无数据） */
    private List<Map<String, Object>> comparisonRows = new ArrayList<>();

    /** 环比的文字结论：前 N 天无数据 / 前 N 天的值 / 环比不可用的原因 */
    private String comparisonNote;

    /**
     * 环比那次查询的绑定参数（Ticket 10 QA P2-1）。
     *
     * <p>光有 {@code comparisonSql} 证明不了窗口挪过 —— 日期是 {@code ?}，本期和环比的 SQL 文本
     * 逐字相同。参数才是「挪了几天」的唯一外部可观测证据（本期 {@code [anchor-days, anchor]}，
     * 环比 {@code [anchor-2d, anchor-d]}）。
     */
    private List<Object> comparisonParams = new ArrayList<>();

    /**
     * 预览 ID（Ticket 12）：非空表示这次运行是「只校验不执行」的预览，值是服务端快照的键。
     *
     * <p>写入方只有一个：{@code AgentEngine.preview()}，在四道闸门全过后由 {@code PreviewStore.save} 产出。
     */
    private String previewId;

    /**
     * 运行结论的显式覆写（Ticket 12）：只有 HITL 两条路径写。
     *
     * <p>{@link RunStatus#of} 的推导规则（闸门里有 REJECT → REJECTED，有错 → FAILED）对普通问数够用，
     * 但覆盖不到两种情形：<b>预览</b>（四条闸门全过、没有任何错误，按推导会是 SUCCESS，
     * 可它根本没执行）和 <b>编辑越界</b>（一个闸门都没跑、按推导会是 FAILED，可它不是故障而是策略拒绝）。
     *
     * <p>写入方唯一，所以不违反「每个字段一个写入方」——非 HITL 路径读到的恒为 null，
     * 行为与 Ticket 11 及之前完全一致。
     */
    private RunStatus runStatus;

    /** 本次编辑被接受的调整项（Ticket 12，来自 {@code IrEditGuard} 的 allowed 列表，回显给人看） */
    private List<String> editAllowed = new ArrayList<>();

    /** 本次编辑越界之处（Ticket 12，来自 {@code IrEditGuard} 的 structural 列表；非空即 REPLAN） */
    private List<String> structuralChanges = new ArrayList<>();

    public List<String> getGateResults() {
        return gateResults;
    }

    public void addGateResult(String gateResult) {
        this.gateResults.add(gateResult);
    }

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

    public Analysis getAnalysis() {
        return analysis;
    }

    public void setAnalysis(Analysis analysis) {
        this.analysis = analysis == null ? Analysis.EMPTY : analysis;
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

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * 明确拒绝（不是故障）：同时写错误文案与「谁拒的」，两者要么都有、要么都没有。
     *
     * <p>不提供 {@code setRejectedBy} 裸 setter，是因为「有文案但没标记」会让这次运行静默落进
     * FAILED（QA-10 P2-3 / QA-11 P2-1 实测：4 类明确拒绝全部被算成失败）。把两个字段捆在一个方法里，
     * 就不存在「只写一半」的中间状态。
     */
    public void reject(String reason, String by) {
        this.errorMessage = reason;
        this.rejectedBy = by;
    }

    public String getRejectedBy() {
        return rejectedBy;
    }

    public List<Object> getComparisonParams() {
        return comparisonParams;
    }

    public void setComparisonParams(List<Object> comparisonParams) {
        this.comparisonParams = comparisonParams == null ? new ArrayList<>() : comparisonParams;
    }

    public Integer getComparisonDays() {
        return comparisonDays;
    }

    public void setComparisonDays(Integer comparisonDays) {
        this.comparisonDays = comparisonDays;
    }

    public String getComparisonSql() {
        return comparisonSql;
    }

    public void setComparisonSql(String comparisonSql) {
        this.comparisonSql = comparisonSql;
    }

    public List<Map<String, Object>> getComparisonRows() {
        return comparisonRows;
    }

    public void setComparisonRows(List<Map<String, Object>> comparisonRows) {
        this.comparisonRows = comparisonRows;
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

    public RunStatus getRunStatus() {
        return runStatus;
    }

    public void setRunStatus(RunStatus runStatus) {
        this.runStatus = runStatus;
    }

    public List<String> getEditAllowed() {
        return editAllowed;
    }

    public void setEditAllowed(List<String> editAllowed) {
        this.editAllowed = editAllowed == null ? new ArrayList<>() : editAllowed;
    }

    public List<String> getStructuralChanges() {
        return structuralChanges;
    }

    public void setStructuralChanges(List<String> structuralChanges) {
        this.structuralChanges = structuralChanges == null ? new ArrayList<>() : structuralChanges;
    }
}
