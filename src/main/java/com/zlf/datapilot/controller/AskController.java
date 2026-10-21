package com.zlf.datapilot.controller;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentEngine;
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

    private AskResponse toResponse(AgentContext ctx, long costMs) {
        AskResponse r = new AskResponse();
        r.setTraceId(ctx.getTraceId());
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
        r.setChartType("table");
        r.setReportHtml("");
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
