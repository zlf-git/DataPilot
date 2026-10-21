package com.zlf.datapilot.controller;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentEngine;
import com.zlf.datapilot.agent.trace.RunStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 问数主入口（Ticket 01）。
 * POST /api/ask {question, sessionId?} → AskResponse（含 IR/SQL/参数/结果/耗时）。
 */
@RestController
@RequestMapping("/api")
public class AskController {

    private final AgentEngine engine;

    public AskController(AgentEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        long start = System.currentTimeMillis();
        AgentContext ctx = engine.run(request.getQuestion(), request.getSessionId());
        long costMs = System.currentTimeMillis() - start;
        return toResponse(ctx, costMs);
    }

    /** 上下文 → 响应体。三条入口（ask / preview / execute）共用，避免各自拼一份导致字段逐渐长歪 */
    static AskResponse toResponse(AgentContext ctx, long costMs) {
        AskResponse r = new AskResponse();
        r.setTraceId(ctx.getTraceId());
        // 运行结论显式回显（Ticket 11）：answer 现在成功时也有内容，判据不能再看「answer 是否为空」
        r.setStatus(RunStatus.of(ctx).name());
        r.setQuestion(ctx.getUserQuestion());
        r.setIr(ctx.getIr());
        r.setSql(ctx.getSqlCommand() != null ? ctx.getSqlCommand().sql() : null);
        r.setParams(ctx.getSqlCommand() != null ? ctx.getSqlCommand().params() : List.of());
        // 五道闸门执行结果回显（如 [存在性=PASS, 权限=PASS, 代价=PASS, 语法=PASS, 结果自检=PASS]）
        r.setGates(ctx.getGateResults());
        r.setRows(ctx.getQueryResult());
        r.setCostMs(costMs);
        r.setPromptTokens(ctx.getPromptTokens());
        r.setCompletionTokens(ctx.getCompletionTokens());
        r.setTotalTokens(ctx.getTotalTokens());
        // answer 优先取结果自检的包装文案（如「无数据」），其次取失败原因，最后空串
        String answer = ctx.getAnswer();
        if (answer == null || answer.isBlank()) {
            answer = ctx.getErrorMessage() != null ? ctx.getErrorMessage() : "";
        }
        r.setAnswer(answer);
        // 解释与报告由 Ticket 11 的节点产出（此前是 "table"/"" 的占位）
        r.setChartType(ctx.getChartType());
        r.setReportHtml(ctx.getReportHtml());
        // 多轮（Ticket 10）：补全后的问题与环比结论一起回显，
        // 否则「那环比呢」这类追问在响应体里看不出系统理解成了什么
        r.setRewrittenQuestion(ctx.getRewrittenQuestion());
        r.setComparisonSql(ctx.getComparisonSql());
        r.setComparisonParams(ctx.getComparisonParams());
        r.setComparisonNote(ctx.getComparisonNote());
        // HITL（Ticket 12）：预览 ID 与「这次编辑被接受/被拒绝在哪」，前端据此渲染确认页
        r.setPreviewId(ctx.getPreviewId());
        r.setEdits(ctx.getEditAllowed());
        r.setStructuralChanges(ctx.getStructuralChanges());
        return r;
    }

    /** 问数请求体 */
    public static class AskRequest {
        private String question;
        private String sessionId;

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }
    }
}
