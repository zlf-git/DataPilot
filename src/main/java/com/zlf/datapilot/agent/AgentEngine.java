package com.zlf.datapilot.agent;

import com.zlf.datapilot.agent.gate.Gate;
import com.zlf.datapilot.agent.gate.GateChain;
import com.zlf.datapilot.agent.gate.GateResult;
import com.zlf.datapilot.agent.session.SessionStore;
import com.zlf.datapilot.agent.step.PromptBuildStep;
import com.zlf.datapilot.agent.step.RecallStep;
import com.zlf.datapilot.agent.step.RewriteStep;
import com.zlf.datapilot.agent.step.SqlExecuteStep;
import com.zlf.datapilot.agent.trace.RunStatus;
import com.zlf.datapilot.agent.trace.TraceRecorder;
import com.zlf.datapilot.agent.trace.TraceSpan;
import com.zlf.datapilot.agent.trace.TraceStep;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.ir.TimeRange;
import com.zlf.datapilot.llm.LlmClient;
import com.zlf.datapilot.llm.LlmGeneration;
import com.zlf.datapilot.sql.IrToSqlConverter;
import com.zlf.datapilot.sql.SqlCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final TraceRecorder traceRecorder;
    private final SessionStore sessions;

    public AgentEngine(RewriteStep rewriteStep,
                       RecallStep recallStep,
                       PromptBuildStep promptBuildStep,
                       LlmClient llmClient,
                       GateChain gateChain,
                       IrToSqlConverter irToSqlConverter,
                       SqlExecuteStep sqlExecuteStep,
                       TraceRecorder traceRecorder,
                       SessionStore sessions) {
        this.rewriteStep = rewriteStep;
        this.recallStep = recallStep;
        this.promptBuildStep = promptBuildStep;
        this.llmClient = llmClient;
        this.gateChain = gateChain;
        this.irToSqlConverter = irToSqlConverter;
        this.sqlExecuteStep = sqlExecuteStep;
        this.traceRecorder = traceRecorder;
        this.sessions = sessions;
    }

    /** LLM span 的输出摘要：IR 形状比 IR 全文有用（全文进 input/output 会把 TEXT 列灌满） */
    private String summarizeIr(QueryIr ir) {
        if (ir == null) {
            return "ir=null";
        }
        return "mode=" + ir.getSelectMode()
                + " metrics=" + (ir.getMetrics() == null ? 0 : ir.getMetrics().size())
                + " dims=" + (ir.getDimensions() == null ? 0 : ir.getDimensions().size())
                + " filters=" + (ir.getFilters() == null ? 0 : ir.getFilters().size())
                + " limit=" + ir.getLimit();
    }

    /** 闸门 span 的输出摘要：PASS / REJECT: 原因 */
    private String summarizeGate(GateResult r) {
        if (r == null) {
            return null;
        }
        return r.isPass() ? (r.getWarning() == null ? "PASS" : "PASS(WARN): " + r.getWarning())
                : "REJECT: " + r.getReason();
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    /**
     * 执行一次问数。返回装满结果的 {@link AgentContext}，失败信息放 errorMessage，不向上抛异常。
     *
     * <p>Ticket 09 起全链路挂埋点：每个执行单元一个 span 写 {@code agent_step}，
     * 收尾在 {@code finally} 里写一条 {@code agent_run} —— 用 finally 而不是在每个 return 前各写一次，
     * 是因为本方法有 7 个提前返回点，漏一个就少一条运行记录，且这种漏通常到线上才发现。
     */
    public AgentContext run(String question, String sessionId) {
        AgentContext ctx = new AgentContext();
        ctx.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        ctx.setSessionId(sessionId);
        ctx.setUserQuestion(question);

        TraceStep trace = traceRecorder.open(ctx.getTraceId());
        try {
            return execute(ctx, trace);
        } finally {
            trace.finish(ctx, RunStatus.of(ctx));
        }
    }

    /** 主链路本体：所有提前返回都走这里，埋点收尾统一在 {@link #run} 的 finally 里做。 */
    private AgentContext execute(AgentContext ctx, TraceStep trace) {
        String question = ctx.getUserQuestion();

        // ⓪ 空问题短路（QA-03 P1-1）：不让空白输入走到 LLM/prompt 匹配，
        // 否则桩会因 few-shot 正文落回 Q1、真模型会自由发挥，两种情况都产出「看着正常」的错数
        if (question == null || question.isBlank()) {
            ctx.setErrorMessage("问题为空，请描述你要查什么");
            return ctx;
        }

        // ① 改写（Ticket 10：多轮补全指代/环比）
        trace.span(TraceSpan.REWRITE, question,
                () -> rewriteStep.execute(ctx), c -> "rewritten=" + c.getRewrittenQuestion());
        // 改写阶段可以就地拒绝（同比无数据、换了维度/时间的追问补不出来）—— 这是「明确拒绝」，
        // 不是失败，但继续往下走会让 LLM 拿一个残缺问题自由发挥，所以在这里就停
        if (ctx.getErrorMessage() != null) {
            return ctx;
        }
        // ② 召回
        trace.span(TraceSpan.RECALL, null,
                () -> recallStep.execute(ctx), c -> "tables=" + c.getRelatedTables());
        // ③ 拼 Prompt
        trace.span(TraceSpan.PROMPT, null,
                () -> promptBuildStep.execute(ctx), c -> "promptChars=" + len(c.getPrompt()));

        // ④ LLM 生成 IR（失败重试 1 次；超时/失败返回提示，不抛 500）
        QueryIr ir = trace.span(TraceSpan.LLM_GENERATE, "question→IR", ctx::getTotalTokens,
                () -> generateIr(ctx), this::summarizeIr);
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
        GateResult irGate = trace.span(TraceSpan.GATE_IR, "phase=IR",
                () -> gateChain.runPhase(Gate.Phase.IR, ctx), this::summarizeGate);
        if (!irGate.isPass()) {
            ctx.setErrorMessage(irGate.getReason());
            return ctx;
        }

        // ⑥ IR → SQL
        try {
            SqlCommand cmd = trace.span(TraceSpan.IR_TO_SQL, "mode=" + ir.getSelectMode(),
                    () -> irToSqlConverter.convert(ir), SqlCommand::sql);
            ctx.setSqlCommand(cmd);
            ctx.setGeneratedSql(cmd.sql());
        } catch (IllegalArgumentException e) {
            ctx.setErrorMessage(e.getMessage());
            return ctx;
        }

        // ⑦ 闸门阶段2（SQL 层）：④语法（JSqlParser 解析 + 表/字段二次比对）
        GateResult sqlGate = trace.span(TraceSpan.GATE_SQL, ctx.getGeneratedSql(),
                () -> gateChain.runPhase(Gate.Phase.SQL, ctx), this::summarizeGate);
        if (!sqlGate.isPass()) {
            ctx.setErrorMessage(sqlGate.getReason());
            return ctx;
        }

        // ⑧ 执行
        trace.span(TraceSpan.EXECUTE, ctx.getGeneratedSql(),
                () -> sqlExecuteStep.execute(ctx), c -> c.getErrorMessage() != null
                        ? "failed" : "rows=" + c.getQueryResult().size());
        if (ctx.getErrorMessage() != null) {
            return ctx;
        }

        // ⑨ 闸门阶段3（结果层）：⑤结果自检（空结果/超长包装，不拒绝）
        GateResult resultGate = trace.span(TraceSpan.GATE_RESULT, "rows=" + ctx.getQueryResult().size(),
                () -> gateChain.runPhase(Gate.Phase.RESULT, ctx), this::summarizeGate);
        if (resultGate.getWarning() != null) {
            ctx.setAnswer(resultGate.getWarning());
        }

        // ⑩ 环比（Ticket 10）：本期结果已经出来，再跑「前 N 天」那个等长窗口。
        // 放在结果自检之后：本期为空/超长截断都已有结论，环比只是补一段对比文字，
        // 不能影响本期结果本身的判定。
        if (ctx.getComparisonDays() != null) {
            runComparison(ctx, ir, ctx.getComparisonDays());
            // 环比结论并进 answer：本期结论（可能来自结果自检的「无数据」包装）在前，
            // 前 N 天在后 —— 用户问「那环比呢」，要的是「本期 X；上期 Y/无数据」这一句
            String base = ctx.getAnswer() != null && !ctx.getAnswer().isBlank()
                    ? ctx.getAnswer() : "本期：" + summarizeRows(ctx.getQueryResult());
            if (ctx.getComparisonNote() != null) {
                ctx.setAnswer(base + "；环比：" + ctx.getComparisonNote());
            }
        }

        // ⑪ 记一轮会话：只记「真的生成了 SQL 并跑通」的轮次，
        // 失败/拒绝的轮次不进上下文 —— 否则下一轮「那环比呢」会补全到一个根本没答出来的问题上
        rememberRound(ctx);
        return ctx;
    }

    /**
     * 环比：把同一份 IR 的时间窗口往前挪 N 天，再走一遍「转换 → 执行」。
     *
     * <p>注意<b>只换了窗口</b>：指标/维度/过滤原样复用，所以不存在「绕过白名单」——
     * 表与字段仍是同一批、已经被本轮的闸门校验过；参数化绑定也仍在（两个日期仍是 {@code ?}）。
     * 代价是环比那次执行不再单独过语法闸门（SQL 文本只差值不同），这一点写进票面「已知边界」。
     */
    private void runComparison(AgentContext ctx, QueryIr ir, int days) {
        QueryIr prev = copyWithOffset(ir, days);
        try {
            SqlCommand cmd = irToSqlConverter.convert(prev);
            ctx.setComparisonSql(cmd.sql());
            AgentContext probe = new AgentContext();
            probe.setTraceId(ctx.getTraceId());
            probe.setSqlCommand(cmd);
            sqlExecuteStep.execute(probe);
            if (probe.getErrorMessage() != null) {
                ctx.setComparisonNote("前 " + days + " 天查询失败，无法计算环比");
                return;
            }
            List<Map<String, Object>> rows = probe.getQueryResult();
            boolean empty = rows == null || rows.isEmpty()
                    || rows.stream().allMatch(row -> row == null || row.values().stream().allMatch(Objects::isNull));
            if (empty) {
                ctx.setComparisonNote("前 " + days + " 天无数据，无法计算环比");
                return;
            }
            ctx.setComparisonRows(rows);
            ctx.setComparisonNote("前 " + days + " 天：" + summarizeRows(rows));
        } catch (IllegalArgumentException e) {
            // 窗口平移本身不合法（天数越界/anchor 缺失）：如实说，不猜一个环比数出来
            ctx.setComparisonNote("环比不可用：" + e.getMessage());
        }
    }

    /** 深拷贝一份只改窗口偏移的 IR（列表共享，因为本轮后续不再写它们） */
    private QueryIr copyWithOffset(QueryIr ir, int offsetDays) {
        QueryIr copy = new QueryIr();
        copy.setQuestion(ir.getQuestion());
        copy.setSelectMode(ir.getSelectMode());
        copy.setMetrics(ir.getMetrics());
        copy.setDimensions(ir.getDimensions());
        copy.setFilters(ir.getFilters());
        copy.setOrderBy(ir.getOrderBy());
        copy.setLimit(ir.getLimit());
        copy.setRatio(ir.getRatio());
        if (ir.getTimeRange() != null) {
            TimeRange shifted = new TimeRange();
            shifted.setRelativeWindow(ir.getTimeRange().getRelativeWindow());
            shifted.setDays(ir.getTimeRange().getDays());
            shifted.setAnchor(ir.getTimeRange().getAnchor());
            shifted.setCompareOffsetDays(offsetDays);
            copy.setTimeRange(shifted);
        }
        return copy;
    }

    /** 一行摘要：1×1 直接给出值，多行给行数（环比结论里塞整表没有意义） */
    private String summarizeRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "无数据";
        }
        if (rows.size() == 1) {
            Map<String, Object> only = rows.get(0);
            if (only != null && only.size() == 1) {
                return String.valueOf(only.values().iterator().next());
            }
            return only == null ? "null" : only.toString();
        }
        return rows.size() + " 行";
    }

    /** 把本轮摘要写进会话存储（供下一轮补全指代/环比） */
    private void rememberRound(AgentContext ctx) {
        if (ctx.getSessionId() == null || ctx.getSessionId().isBlank() || ctx.getSqlCommand() == null) {
            return;
        }
        QueryIr ir = ctx.getIr();
        List<String> metricIds = ir == null || ir.getMetrics() == null ? List.of()
                : ir.getMetrics().stream()
                .map(com.zlf.datapilot.ir.MetricRef::getMetricId)
                .filter(Objects::nonNull)
                .toList();
        List<String> dims = ir == null || ir.getDimensions() == null ? List.of()
                : ir.getDimensions().stream()
                .map(com.zlf.datapilot.ir.DimensionRef::getField)
                .filter(Objects::nonNull)
                .toList();
        Integer days = ir == null || ir.getTimeRange() == null ? null : ir.getTimeRange().getDays();
        sessions.append(ctx.getSessionId(), new SessionStore.Round(
                ctx.getUserQuestion(),
                ctx.getRewrittenQuestion(),
                metricIds,
                dims,
                days,
                ctx.getGeneratedSql(),
                System.currentTimeMillis()));
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
