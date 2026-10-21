package com.zlf.datapilot.agent;

import com.zlf.datapilot.agent.gate.Gate;
import com.zlf.datapilot.agent.gate.GateChain;
import com.zlf.datapilot.agent.gate.GateResult;
import com.zlf.datapilot.agent.step.PromptBuildStep;
import com.zlf.datapilot.agent.step.RecallStep;
import com.zlf.datapilot.agent.step.RewriteStep;
import com.zlf.datapilot.agent.step.SqlExecuteStep;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.llm.LlmClient;
import com.zlf.datapilot.llm.LlmGeneration;
import com.zlf.datapilot.sql.IrToSqlConverter;
import com.zlf.datapilot.sql.SqlCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.UUID;

/**
 * Agent 编排器：把主链路串起来。
 * Rewrite → Recall → Prompt → LLM(生成 IR)
 *   → 闸门阶段1(①存在性 → ②权限 → ③代价)
 *   → IR 转 SQL → 闸门阶段2(④语法)
 *   → 执行 → 闸门阶段3(⑤结果自检) → 返回。
 * 埋点（A1）、解释/报告（A3）、HITL（A6）等由后续 ticket 在此链路前后扩展。
 */
@Component
public class AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    private final RewriteStep rewriteStep;
    private final RecallStep recallStep;
    private final PromptBuildStep promptBuildStep;
    private final LlmClient llmClient;
    private final GateChain gateChain;
    private final IrToSqlConverter irToSqlConverter;
    private final SqlExecuteStep sqlExecuteStep;

    public AgentEngine(RewriteStep rewriteStep,
                       RecallStep recallStep,
                       PromptBuildStep promptBuildStep,
                       LlmClient llmClient,
                       GateChain gateChain,
                       IrToSqlConverter irToSqlConverter,
                       SqlExecuteStep sqlExecuteStep) {
        this.rewriteStep = rewriteStep;
        this.recallStep = recallStep;
        this.promptBuildStep = promptBuildStep;
        this.llmClient = llmClient;
        this.gateChain = gateChain;
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

        // ④ LLM 生成 IR（失败重试 1 次；超时/失败返回提示，不抛 500）
        QueryIr ir = generateIr(ctx);
        if (ir == null) {
            // generateIr 已按异常类型设置 errorMessage（超时 vs 服务异常）
            return ctx;
        }
        ctx.setIr(ir);
        // 完全空 IR（既无指标也无维度）→ 无法识别；仅 metrics 空的明细查询交给闸门/后续 ticket 处理
        boolean noMetrics = ir.getMetrics() == null || ir.getMetrics().isEmpty();
        boolean noDims = ir.getDimensions() == null || ir.getDimensions().isEmpty();
        if (noMetrics && noDims) {
            ctx.setErrorMessage("无法识别查询意图，请换个问法");
            return ctx;
        }

        // ⑤ 闸门阶段1（IR 层）：①存在性 → ②权限 → ③代价
        GateResult irGate = gateChain.runPhase(Gate.Phase.IR, ctx);
        if (!irGate.isPass()) {
            ctx.setErrorMessage(irGate.getReason());
            return ctx;
        }

        // ⑥ IR → SQL
        try {
            SqlCommand cmd = irToSqlConverter.convert(ir);
            ctx.setSqlCommand(cmd);
            ctx.setGeneratedSql(cmd.sql());
        } catch (IllegalArgumentException e) {
            ctx.setErrorMessage(e.getMessage());
            return ctx;
        }

        // ⑦ 闸门阶段2（SQL 层）：④语法（JSqlParser 解析 + 表/字段二次比对）
        GateResult sqlGate = gateChain.runPhase(Gate.Phase.SQL, ctx);
        if (!sqlGate.isPass()) {
            ctx.setErrorMessage(sqlGate.getReason());
            return ctx;
        }

        // ⑧ 执行
        sqlExecuteStep.execute(ctx);
        if (ctx.getErrorMessage() != null) {
            return ctx;
        }

        // ⑨ 闸门阶段3（结果层）：⑤结果自检（空结果/超长包装，不拒绝）
        GateResult resultGate = gateChain.runPhase(Gate.Phase.RESULT, ctx);
        if (resultGate.getWarning() != null) {
            ctx.setAnswer(resultGate.getWarning());
        }

        return ctx;
    }

    private QueryIr generateIr(AgentContext ctx) {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                LlmGeneration gen = llmClient.generate(ctx.getSystemPrompt(), ctx.getPrompt());
                recordUsage(ctx, gen);
                return gen.getIr();
            } catch (Exception e) {
                lastFailure = e;
                // 只打一行简短日志，不打堆栈全文（后续 AgentOps 会按 trace 记录，堆栈会淹掉有效信息）
                log.warn("LLM 生成失败（第 {} 次）：{}", attempt + 1, brief(e));
            }
        }
        // 两次都失败：按异常类型分类报文案，避免把连接拒绝/鉴权失败/解析失败全归成「超时」
        ctx.setErrorMessage(classifyLlmError(lastFailure));
        return null;
    }

    private void recordUsage(AgentContext ctx, LlmGeneration gen) {
        if (gen != null) {
            ctx.setPromptTokens(gen.getPromptTokens());
            ctx.setCompletionTokens(gen.getCompletionTokens());
            ctx.setTotalTokens(gen.getTotalTokens());
        }
    }

    /** 超时单独报「超时」，其余（连接拒绝/401/JSON 解析失败/结构化输出不合法）统一报「服务异常」 */
    private String classifyLlmError(Throwable t) {
        if (isTimeout(t)) {
            return "模型响应超时，请重试";
        }
        return "模型服务异常（鉴权失败或返回无法解析），请稍后重试";
    }

    private boolean isTimeout(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SocketTimeoutException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && (msg.toLowerCase().contains("timeout") || msg.toLowerCase().contains("timed out"))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 截断异常信息，避免日志里刷长文本 */
    private String brief(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return t.getClass().getSimpleName();
        }
        return t.getClass().getSimpleName() + ": " + (msg.length() > 120 ? msg.substring(0, 120) + "..." : msg);
    }
}
