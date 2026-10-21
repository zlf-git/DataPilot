package com.zlf.datapilot.agent;

import com.zlf.datapilot.agent.step.PromptBuildStep;
import com.zlf.datapilot.agent.step.RecallStep;
import com.zlf.datapilot.agent.step.RewriteStep;
import com.zlf.datapilot.agent.step.SqlExecuteStep;
import com.zlf.datapilot.agent.step.SqlValidateStep;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.llm.LlmClient;
import com.zlf.datapilot.llm.LlmGeneration;
import com.zlf.datapilot.sql.IrToSqlConverter;
import com.zlf.datapilot.sql.SqlCommand;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Agent 编排器：把主链路串起来。
 * Rewrite → Recall → Prompt → LLM(生成 IR) → Validate(闸门空实现) → IR→SQL → Execute。
 * 埋点（A1）、解释/报告（A3）、HITL（A6）等由后续 ticket 在此链路前后扩展。
 */
@Component
public class AgentEngine {

    private final RewriteStep rewriteStep;
    private final RecallStep recallStep;
    private final PromptBuildStep promptBuildStep;
    private final LlmClient llmClient;
    private final SqlValidateStep sqlValidateStep;
    private final IrToSqlConverter irToSqlConverter;
    private final SqlExecuteStep sqlExecuteStep;

    public AgentEngine(RewriteStep rewriteStep,
                       RecallStep recallStep,
                       PromptBuildStep promptBuildStep,
                       LlmClient llmClient,
                       SqlValidateStep sqlValidateStep,
                       IrToSqlConverter irToSqlConverter,
                       SqlExecuteStep sqlExecuteStep) {
        this.rewriteStep = rewriteStep;
        this.recallStep = recallStep;
        this.promptBuildStep = promptBuildStep;
        this.llmClient = llmClient;
        this.sqlValidateStep = sqlValidateStep;
        this.irToSqlConverter = irToSqlConverter;
        this.sqlExecuteStep = sqlExecuteStep;
    }

    /**
     * 执行一次问数。返回装满结果的 {@link AgentContext}，失败信息放 errorMessage，不向上抛异常。
     */
    public AgentContext run(String question, String sessionId) {
        AgentContext ctx = new AgentContext();
        ctx.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        ctx.setSessionId(sessionId);
        ctx.setUserQuestion(question);

        // ① 改写（Ticket 01 透传）
        rewriteStep.execute(ctx);
        // ② 召回
        recallStep.execute(ctx);
        // ③ 拼 Prompt
        promptBuildStep.execute(ctx);

        // ④ LLM 生成 IR（失败重试 1 次，超时/失败返回提示，不抛 500）
        QueryIr ir = generateIr(ctx);
        if (ir == null) {
            ctx.setErrorMessage("模型响应超时，请重试");
            return ctx;
        }
        ctx.setIr(ir);
        if (ir.getMetrics() == null || ir.getMetrics().isEmpty()) {
            ctx.setErrorMessage("无法识别查询意图，请换个问法");
            return ctx;
        }

        // ⑤ 闸门（Ticket 01 空实现）
        sqlValidateStep.execute(ctx);

        // ⑥ IR → SQL
        try {
            SqlCommand cmd = irToSqlConverter.convert(ir);
            ctx.setSqlCommand(cmd);
            ctx.setGeneratedSql(cmd.sql());
        } catch (IllegalArgumentException e) {
            ctx.setErrorMessage(e.getMessage());
            return ctx;
        }

        // ⑦ 执行
        sqlExecuteStep.execute(ctx);
        return ctx;
    }

    private QueryIr generateIr(AgentContext ctx) {
        try {
            LlmGeneration gen = llmClient.generate(ctx.getSystemPrompt(), ctx.getPrompt());
            return gen.getIr();
        } catch (Exception first) {
            // 失败重试 1 次
            try {
                LlmGeneration gen = llmClient.generate(ctx.getSystemPrompt(), ctx.getPrompt());
                return gen.getIr();
            } catch (Exception second) {
                return null;
            }
        }
    }
}
