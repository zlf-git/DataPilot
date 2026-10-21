package com.zlf.datapilot.agent.trace;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.config.DatapilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 单次问数的埋点器（Ticket 09）：由 {@link TraceRecorder#open(String)} 产出，生命周期 = 一次 ask。
 *
 * <p>用法（横切在 AgentEngine 一处，不改各 Step 的签名）：
 * <pre>{@code
 * trace.span(TraceSpan.EXECUTE, sql, () -> sqlExecuteStep.execute(ctx), c -> "rows=" + c.getQueryResult().size());
 * }</pre>
 *
 * <p><b>三条硬约束</b>：
 * <ol>
 *   <li><b>埋点不能影响问数</b>：写库异常一律吞掉只打 warn。AgentOps 是旁路，
 *       数据库抖动导致问数失败是本末倒置。</li>
 *   <li><b>span 里的异常必须原样抛出</b>：{@code finally} 里写库、异常继续往上走，
 *       让 AgentEngine 原有的分类处理（超时/服务异常）保持不变。</li>
 *   <li><b>列宽先截断</b>：{@code agent_run.question/error_msg} 是 VARCHAR(500)，
 *       LLM 的 errorMessage 或超长问题直接插会抛 "Data too long"，把埋点整条丢掉。</li>
 * </ol>
 */
public class TraceStep {

    private static final Logger log = LoggerFactory.getLogger(TraceStep.class);

    /** agent_run.question / error_msg 列宽 */
    static final int QUESTION_MAX = 500;
    static final int ERROR_MAX = 500;
    /** agent_step.input/output 是 TEXT，仍截一刀防止 prompt 全文入库 */
    static final int FIELD_MAX = 2000;
    /** agent_run.final_sql 是 TEXT */
    static final int SQL_MAX = 8000;

    private final AgentTraceRepository repo;
    private final DatapilotProperties.Cost pricing;
    private final String traceId;
    private final LocalDateTime runStartAt = LocalDateTime.now();
    private final long runStartMs = System.currentTimeMillis();

    private int order = 0;

    public TraceStep(AgentTraceRepository repo, DatapilotProperties.Cost pricing, String traceId) {
        this.repo = repo;
        this.pricing = pricing;
        this.traceId = traceId;
    }

    /**
     * 记录一个 span 并把结果原样返回。
     *
     * @param span     span 目录项（决定 step_name 与 span_type）
     * @param input    输入摘要（问题/SQL/阶段名，进 agent_step.input）
     * @param body     真正干活的逻辑；抛出的异常会在落库后继续抛出
     * @param describe 把 body 结果转成输出摘要；返回 null 表示无需输出摘要
     */
    public <T> T span(TraceSpan span, String input, Supplier<T> body, Function<T, String> describe) {
        return span(span, input, () -> 0, body, describe);
    }

    /**
     * 带 token 用量的 span（只有 {@link SpanType#LLM} 需要）：token 在 body 跑完后才读得到，
     * 所以用 {@code IntSupplier} 延迟取值，不能提前算。
     */
    public <T> T span(TraceSpan span, String input, IntSupplier tokens,
                      Supplier<T> body, Function<T, String> describe) {
        int seq = ++order;
        LocalDateTime startAt = LocalDateTime.now();
        long startMs = System.currentTimeMillis();
        String output = null;
        String error = null;
        SpanStatus status = SpanStatus.SUCCESS;
        int used = 0;
        try {
            T result = body.get();
            output = describe == null ? null : describe.apply(result);
            used = tokens.getAsInt();
            return result;
        } catch (RuntimeException e) {
            status = SpanStatus.FAILED;
            error = brief(e);
            throw e;
        } finally {
            long latency = System.currentTimeMillis() - startMs;
            write(new AgentTraceRepository.StepRow(traceId, span.code(), span.type().code(), seq,
                    status.name(), latency, clip(input, FIELD_MAX), clip(output, FIELD_MAX),
                    used, clip(error, ERROR_MAX), startAt, LocalDateTime.now()));
        }
    }

    /** 收尾：写 agent_run（token/cost/latency/最终 SQL/失败原因）。每次运行必须恰好调用一次。 */
    public void finish(AgentContext ctx, RunStatus status) {
        BigDecimal cost = costOf(ctx.getPromptTokens(), ctx.getCompletionTokens());
        write(new AgentTraceRepository.RunRow(traceId, clip(ctx.getSessionId(), 64),
                clip(ctx.getUserQuestion(), QUESTION_MAX), status.name(),
                System.currentTimeMillis() - runStartMs, ctx.getTotalTokens(), cost,
                clip(ctx.getGeneratedSql(), SQL_MAX), clip(ctx.getErrorMessage(), ERROR_MAX),
                runStartAt, LocalDateTime.now()));
    }

    /** 成本（元）：按 datapilot.cost 的每百万 token 单价算，保留 6 位（DECIMAL(10,6)） */
    BigDecimal costOf(int promptTokens, int completionTokens) {
        BigDecimal in = BigDecimal.valueOf(pricing.getInputPricePerMillion())
                .multiply(BigDecimal.valueOf(promptTokens))
                .divide(BigDecimal.valueOf(1_000_000L), 10, RoundingMode.HALF_UP);
        BigDecimal out = BigDecimal.valueOf(pricing.getOutputPricePerMillion())
                .multiply(BigDecimal.valueOf(completionTokens))
                .divide(BigDecimal.valueOf(1_000_000L), 10, RoundingMode.HALF_UP);
        return in.add(out).setScale(6, RoundingMode.HALF_UP);
    }

    /** 已记录的 span 数（测试与排障用） */
    public int spanCount() {
        return order;
    }

    public String traceId() {
        return traceId;
    }

    private void write(RunnableEx action) {
        // 见类注释约束 1：埋点失败只能降级成日志
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("AgentOps 埋点写入失败 trace={}：{}（问数不受影响）", traceId, brief(e));
        }
    }

    private void write(AgentTraceRepository.StepRow row) {
        write(() -> repo.saveStep(row));
    }

    private void write(AgentTraceRepository.RunRow row) {
        write(() -> repo.saveRun(row));
    }

    /** 截断到列宽，null 原样返回 */
    static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String brief(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return t.getClass().getSimpleName();
        }
        return t.getClass().getSimpleName() + ": " + (msg.length() > 200 ? msg.substring(0, 200) + "..." : msg);
    }

    @FunctionalInterface
    private interface RunnableEx {
        void run();
    }
}
