package com.zlf.datapilot.agent;

import com.zlf.datapilot.agent.gate.Gate;
import com.zlf.datapilot.agent.gate.GateChain;
import com.zlf.datapilot.agent.gate.GateResult;
import com.zlf.datapilot.agent.hitl.IrEditGuard;
import com.zlf.datapilot.agent.hitl.PreviewStore;
import com.zlf.datapilot.agent.session.SessionStore;
import com.zlf.datapilot.agent.step.ExplainStep;
import com.zlf.datapilot.agent.step.PromptBuildStep;
import com.zlf.datapilot.agent.step.RecallStep;
import com.zlf.datapilot.agent.step.ReportStep;
import com.zlf.datapilot.agent.step.RewriteStep;
import com.zlf.datapilot.agent.step.SqlExecuteStep;
import com.zlf.datapilot.agent.trace.RunStatus;
import com.zlf.datapilot.agent.trace.SpanStatus;
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
    private final ExplainStep explainStep;
    private final ReportStep reportStep;
    private final TraceRecorder traceRecorder;
    private final SessionStore sessions;
    private final PreviewStore previews;

    public AgentEngine(RewriteStep rewriteStep,
                       RecallStep recallStep,
                       PromptBuildStep promptBuildStep,
                       LlmClient llmClient,
                       GateChain gateChain,
                       IrToSqlConverter irToSqlConverter,
                       SqlExecuteStep sqlExecuteStep,
                       ExplainStep explainStep,
                       ReportStep reportStep,
                       TraceRecorder traceRecorder,
                       SessionStore sessions,
                       PreviewStore previews) {
        this.rewriteStep = rewriteStep;
        this.recallStep = recallStep;
        this.promptBuildStep = promptBuildStep;
        this.llmClient = llmClient;
        this.gateChain = gateChain;
        this.irToSqlConverter = irToSqlConverter;
        this.sqlExecuteStep = sqlExecuteStep;
        this.explainStep = explainStep;
        this.reportStep = reportStep;
        this.traceRecorder = traceRecorder;
        this.sessions = sessions;
        this.previews = previews;
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

    /** 新建一次运行的上下文（traceId 一次请求一个，三条入口共用） */
    private AgentContext newContext(String question, String sessionId) {
        AgentContext ctx = new AgentContext();
        ctx.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        ctx.setSessionId(sessionId);
        ctx.setUserQuestion(question);
        return ctx;
    }

    /**
     * 执行一次问数。返回装满结果的 {@link AgentContext}，失败信息放 errorMessage，不向上抛异常。
     *
     * <p>Ticket 09 起全链路挂埋点：每个执行单元一个 span 写 {@code agent_step}，
     * 收尾在 {@code finally} 里写一条 {@code agent_run} —— 用 finally 而不是在每个 return 前各写一次，
     * 是因为本方法有 7 个提前返回点，漏一个就少一条运行记录，且这种漏通常到线上才发现。
     */
    public AgentContext run(String question, String sessionId) {
        AgentContext ctx = newContext(question, sessionId);
        TraceStep trace = traceRecorder.open(ctx.getTraceId());
        try {
            return execute(ctx, trace);
        } finally {
            trace.finish(ctx, RunStatus.of(ctx));
        }
    }

    /** 一锤到底：准备 + 执行。任一步出错就停在那里，不再往下走。 */
    private AgentContext execute(AgentContext ctx, TraceStep trace) {
        prepare(ctx, trace);
        return ctx.getErrorMessage() != null ? ctx : finish(ctx, trace);
    }

    /**
     * 准备段（Ticket 12 拆出）：问题 → 改写 → 召回 → Prompt → LLM 生成 IR → 闸门①②③ → SQL → 闸门④。
     *
     * <p>拆的理由只有一个：HITL 的「预览」要求跑到「SQL 生成且四道闸门全过」为止就停下，
     * 而「确认执行」要求从 IR 直接续上、<b>不重新调 LLM</b>（再调一次就可能生成一份
     * 与用户刚看过的不同的 SQL，那预览就白看了）。切成两段之后，两条入口各自只走自己那一段。
     *
     * <p>参数化 SQL 在 prepare 末尾就绪（{@code ctx.sqlCommand}），因此「预览」返回的 SQL 与
     * 「不预览直接问」返回的 SQL 是同一份代码产出的同一形状。
     */
    private AgentContext prepare(AgentContext ctx, TraceStep trace) {
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
            // 「没听懂」不是系统故障（QA-10 P2-3 / QA-11 P2-1）：它属于策略/能力边界，
            // 落 REJECTED 才不会把「问得不对」算进故障率
            ctx.reject("无法识别查询意图，请换个问法", "identify");
            return ctx;
        }

        // ⑤⑥⑦ 闸门①②③ → SQL → 闸门④
        if (!runIrGates(ctx, trace, "phase=IR") || !convertToSql(ctx, trace) || !runSqlGate(ctx, trace, "phase=SQL")) {
            return ctx;
        }
        return ctx;
    }

    /**
     * 执行段（Ticket 12 拆出）：执行 → 闸门⑤ → 环比 → 解释 → 报告 → 记会话。
     *
     * <p>要求 {@code ctx.ir} 与 {@code ctx.sqlCommand} 已就绪 —— 普通问数由 {@link #prepare} 备好，
     * HITL 由 {@link #executePreview} 用「确认过的 IR」重新备好。
     */
    private AgentContext finish(AgentContext ctx, TraceStep trace) {
        QueryIr ir = ctx.getIr();

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

        // ⑪ 解释（Ticket 11）：answer 已由闸门/环比填过就不覆盖，只补空的 + 定图表类型
        trace.span(TraceSpan.EXPLAIN, "rows=" + ctx.getQueryResult().size(),
                () -> explainStep.execute(ctx), c -> "chartType=" + c.getChartType());
        // ⑫ 报告（Ticket 11）：按结果行渲染 ECharts HTML（series 数 = 行数）
        trace.span(TraceSpan.REPORT, "chartType=" + ctx.getChartType(),
                () -> reportStep.execute(ctx), c -> "htmlChars=" + len(c.getReportHtml()));

        // ⑬ 记一轮会话：只记「真的生成了 SQL 并跑通」的轮次，
        // 失败/拒绝的轮次不进上下文 —— 否则下一轮「那环比呢」会补全到一个根本没答出来的问题上
        rememberRound(ctx);
        return ctx;
    }

    /** ⑤ 闸门阶段1（IR 层）：①存在性 → ②权限 → ③代价。返回 false 表示已被拦下 */
    private boolean runIrGates(AgentContext ctx, TraceStep trace, String label) {
        GateResult irGate = trace.span(TraceSpan.GATE_IR, label,
                () -> gateChain.runPhase(Gate.Phase.IR, ctx), this::summarizeGate);
        if (!irGate.isPass()) {
            ctx.setErrorMessage(irGate.getReason());
            return false;
        }
        return true;
    }

    /** ⑥ IR → SQL。返回 false 表示生成器显式拒绝（如跨表维度） */
    private boolean convertToSql(AgentContext ctx, TraceStep trace) {
        QueryIr ir = ctx.getIr();
        try {
            SqlCommand cmd = trace.span(TraceSpan.IR_TO_SQL, "mode=" + ir.getSelectMode(),
                    () -> irToSqlConverter.convert(ir), SqlCommand::sql);
            ctx.setSqlCommand(cmd);
            ctx.setGeneratedSql(cmd.sql());
            return true;
        } catch (IllegalArgumentException e) {
            // 生成器主动拒绝（跨表维度、窗口类型不支持…）：这是「这个问法当前不支持」，不是崩溃
            ctx.reject(e.getMessage(), "converter");
            return false;
        }
    }

    /** ⑦ 闸门阶段2（SQL 层）：④语法（JSqlParser 解析 + 表/字段二次比对） */
    private boolean runSqlGate(AgentContext ctx, TraceStep trace, String label) {
        GateResult sqlGate = trace.span(TraceSpan.GATE_SQL, label + " " + ctx.getGeneratedSql(),
                () -> gateChain.runPhase(Gate.Phase.SQL, ctx), this::summarizeGate);
        if (!sqlGate.isPass()) {
            ctx.setErrorMessage(sqlGate.getReason());
            return false;
        }
        return true;
    }

    /**
     * HITL 第一段（Ticket 12）：只校验不执行。
     *
     * <p>跑到 {@link #prepare} 结束就停 —— 四道闸门全过、SQL 已生成，但<b>一条也没往数据源上打</b>。
     * 停在这里是票面的硬要求：「preview 的返回值里出现任何一行数据，都说明它在偷偷执行」。
     *
     * <p>成功时 run 状态落 {@link RunStatus#PREVIEW}（不是 SUCCESS）：这次运行确实什么都没查出来，
     * 把它混进成功率会让「平均延迟/成功率」这类指标悄悄变好看。
     */
    public AgentContext preview(String question, String sessionId) {
        return preview(question, sessionId, PreviewStore.ANONYMOUS);
    }

    /**
     * 预览（带身份，Ticket 14）：生成并校验 SQL，不执行，返回待确认的快照。
     *
     * @param owner 当前请求的身份（匿名传 {@link PreviewStore#ANONYMOUS}）。
     *              它会被写进快照，执行时比对 —— 详见 {@link PreviewStore#save(AgentContext, String)}
     */
    public AgentContext preview(String question, String sessionId, String owner) {
        AgentContext ctx = newContext(question, sessionId);
        TraceStep trace = traceRecorder.open(ctx.getTraceId());
        try {
            prepare(ctx, trace);
            if (ctx.getErrorMessage() != null) {
                // 预览阶段就被拦：按推导规则落 REJECTED/FAILED，不补 hitl span（没有「待确认」这回事）
                return ctx;
            }
            PreviewStore.Preview snapshot = previews.save(ctx, owner);
            ctx.setPreviewId(snapshot.previewId());
            trace.mark(TraceSpan.HITL_PREVIEW, "previewId=" + snapshot.previewId(),
                    "sql=" + len(ctx.getGeneratedSql()) + "chars, 未执行, ttl=" + previews.ttlMinutes() + "min",
                    SpanStatus.PREVIEW);
            ctx.setRunStatus(RunStatus.PREVIEW);
            ctx.setAnswer("已生成 SQL 并通过四道闸门校验，尚未执行；确认后提交 /api/ask/execute（"
                    + previews.ttlText() + "内有效）");
            return ctx;
        } finally {
            trace.finish(ctx, RunStatus.of(ctx));
        }
    }

    /**
     * HITL 第二段（Ticket 12）：按预览快照执行，<b>重跑闸门 1~4</b>。
     *
     * <p>三个刻意的决定：
     * <ol>
     *   <li><b>不复用预览生成的 SQL 文本</b>，而是拿 IR 重新 {@code convert}。SQL 文本一旦被人工经手，
     *       就再也说不清「它还是不是原来那条被校验过的查询」；重新生成则是「同一份 IR → 同一段代码 → SQL」，
     *       闸门校验的对象与真正执行的对象必然是同一个。</li>
     *   <li><b>不重新调 LLM</b>。再生成一次 IR 会得到一份用户没看过的查询，预览的意义就没了。</li>
     *   <li><b>编辑越界直接 REPLAN</b>，不做「尽力而为地部分应用」——只应用一半的编辑，用户看到的
     *       执行结果会既不是他改的那份、也不是预览那份，是最坏的一种。</li>
     * </ol>
     */
    public AgentContext executePreview(PreviewStore.Preview preview, QueryIr submitted) {
        return executePreview(preview, submitted, preview.owner());
    }

    /**
     * 执行一份预览快照（带身份，Ticket 14 / QA-12 P2-3）。
     *
     * <p><b>身份不属于这份快照就直接拒，且一条 SQL 都不发</b>。判断放在最前面，
     * 在建上下文、开 trace 之前就想清楚：越界的请求不值得一次 span，也不该进会话。
     */
    public AgentContext executePreview(PreviewStore.Preview preview, QueryIr submitted, String owner) {
        if (!preview.ownedBy(owner)) {
            // 越界执行是一次安全事件，必须留痕 —— 但只进应用日志、不落 agent_run：
            // 它没跑任何东西，记成一条运行会让 PASS/REJECTED 的统计失真。
            log.warn("拒绝跨身份执行预览：previewId={} owner={} requester={} sessionId={}",
                    preview.previewId(), preview.owner(), owner, preview.sessionId());
            AgentContext denied = newContext(preview.question(), preview.sessionId());
            denied.setPreviewId(preview.previewId());
            denied.reject("这份预览不是你生成的（预览时身份：" + preview.owner()
                    + "，当前身份：" + owner + "），请用自己的凭证重新预览", "hitl");
            return denied;
        }
        AgentContext ctx = newContext(preview.question(), preview.sessionId());
        ctx.setRewrittenQuestion(preview.rewrittenQuestion());
        ctx.setPreviewId(preview.previewId());
        // 环比天数不在 IR 里（RewriteStep 就地标的），必须从快照恢复，
        // 否则「那环比呢」的预览执行出来只有本期，与直接问的行为不一致
        ctx.setComparisonDays(preview.comparisonDays());

        TraceStep trace = traceRecorder.open(ctx.getTraceId());
        try {
            QueryIr stored = previews.parseIr(preview);
            IrEditGuard.Verdict verdict = IrEditGuard.diff(stored, submitted);

            if (verdict.replan()) {
                // QA-12 P3-5：拒绝时不能回显 edits —— 「结构性拒绝 + 同时回显已接受的编辑」
                // 读起来像「部分应用」，而本方法明确不做部分应用（一处都不应用）
                ctx.setEditAllowed(List.of());
                ctx.setStructuralChanges(verdict.structural());
                ctx.setErrorMessage("编辑超出可调范围，请重新问一遍生成新的预览：" + verdict.reason()
                        + "（可调整的只有：过滤条件的值、结果条数、排序方向、维度）");
                ctx.setRunStatus(RunStatus.REJECTED);
                trace.mark(TraceSpan.HITL_EXECUTE, "previewId=" + preview.previewId(),
                        "REPLAN: " + verdict.reason(), SpanStatus.REPLAN);
                return ctx;
            }

            QueryIr target = submitted != null ? submitted : stored;
            ctx.setEditAllowed(verdict.allowed());
            // anchor / 环比平移是服务端字段：执行前清空，让生成器按此刻的数据重新解析锚点
            IrEditGuard.enforceServerFields(target);
            // 维度去重（QA-12 P3-6）：重复维度生成 GROUP BY a, a，执行得通但没意义
            IrEditGuard.dedupeDimensions(target);
            ctx.setIr(target);

            trace.mark(TraceSpan.HITL_EXECUTE, "previewId=" + preview.previewId()
                            + ", edits=" + verdict.allowed().size(),
                    verdict.allowed().isEmpty() ? "按预览原样执行"
                            : "接受编辑：" + String.join("；", verdict.allowed()),
                    SpanStatus.SUCCESS);

            // 重跑闸门 1~4：编辑后的 IR 与重新生成的 SQL 都要重新过一遍
            if (!runIrGates(ctx, trace, "phase=IR(confirmed)")
                    || !convertToSql(ctx, trace)
                    || !runSqlGate(ctx, trace, "phase=SQL(confirmed)")) {
                return ctx;
            }
            return finish(ctx, trace);
        } finally {
            trace.finish(ctx, RunStatus.of(ctx));
        }
    }

    /**
     * 环比：把同一份 IR 的时间窗口往前挪 N 天，再走一遍「转换 → 执行」。
     *
     * <p>注意<b>只换了窗口</b>：指标/维度/过滤原样复用，所以不存在「绕过白名单」——
     * 表与字段仍是同一批、已经被本轮的闸门校验过；参数化绑定也仍在（两个日期仍是 {@code ?}）。
     * 代价是环比那次执行不再单独过语法闸门（SQL 文本只差值不同），这一点写进票面「已知边界」。
     */
    private void runComparison(AgentContext ctx, QueryIr ir, int days) {
        // 兜底第二层（QA-10 P1-1）：RewriteStep 已经拦了「上一轮没有相对窗口」，但别的入口
        // （HITL 恢复、将来的前端直连）也可能带着一个没有 timeRange 的 IR 走到这里。
        // 单日题的日期在 filters 里，copyWithOffset 挪不动它 —— 那第二次查询就是本期的复读，
        // 报出去等于「环比持平」。这里直接判不可用，不执行。
        if (ir == null || ir.getTimeRange() == null) {
            ctx.setComparisonNote("环比不可用：上一轮没有相对时间窗口（按天查询），请用「近 N 天」这类问题再问一次");
            return;
        }
        QueryIr prev = copyWithOffset(ir, days);
        try {
            SqlCommand cmd = irToSqlConverter.convert(prev);
            ctx.setComparisonSql(cmd.sql());
            // 参数是「窗口真的挪了」的唯一外部可观测证据（SQL 文本两边逐字相同，日期都是 ?）
            ctx.setComparisonParams(cmd.params());
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
