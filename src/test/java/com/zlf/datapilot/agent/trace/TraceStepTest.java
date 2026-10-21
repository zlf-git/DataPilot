package com.zlf.datapilot.agent.trace;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.config.DatapilotProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 埋点器单测（Ticket 09）。不连库：agent_step/agent_run 的写入用 mock 仓储捕获，
 * 断言的是「落库的内容与顺序」而不是「SQL 能不能执行」——后者由 e2e（qa/verify_traces.py）在真库上验。
 *
 * <p>span 先攒内存、{@code finish()} 时一次 batch 落库（原因见 TraceStep 类注释），
 * 所以下面每个用例都要显式调 {@code finish()} 才能捕获到 span。
 */
class TraceStepTest {

    private final AgentTraceRepository repo = mock(AgentTraceRepository.class);
    private final DatapilotProperties props = new DatapilotProperties();

    private TraceStep trace() {
        return new TraceStep(repo, props.getCost(), "t-0001");
    }

    private AgentContext ctx(String question) {
        AgentContext c = new AgentContext();
        c.setUserQuestion(question);
        return c;
    }

    /** span 与 run 是一次 saveTrace 提交的，两个 helper 各自抓自己那半 */
    @SuppressWarnings("unchecked")
    private List<AgentTraceRepository.StepRow> flushedSteps() {
        ArgumentCaptor<List<AgentTraceRepository.StepRow>> cap = ArgumentCaptor.forClass(List.class);
        verify(repo).saveTrace(cap.capture(), any());
        return cap.getValue();
    }

    private AgentTraceRepository.RunRow flushedRun() {
        ArgumentCaptor<AgentTraceRepository.RunRow> cap = ArgumentCaptor.forClass(AgentTraceRepository.RunRow.class);
        verify(repo).saveTrace(anyList(), cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("span 按执行顺序编号，step_name/span_type 取自 span 目录")
    void spansAreOrderedAndTyped() {
        TraceStep t = trace();
        t.span(TraceSpan.REWRITE, "q", () -> "q", s -> "rewritten=" + s);
        t.span(TraceSpan.EXECUTE, "select 1", () -> 3, n -> "rows=" + n);
        t.finish(ctx("q"), RunStatus.SUCCESS);

        List<AgentTraceRepository.StepRow> rows = flushedSteps();
        assertThat(rows).hasSize(2);

        assertThat(rows.get(0).stepOrder()).isEqualTo(1);
        assertThat(rows.get(0).stepName()).isEqualTo("rewrite");
        assertThat(rows.get(0).spanType()).isEqualTo("operation");
        assertThat(rows.get(0).status()).isEqualTo("SUCCESS");
        assertThat(rows.get(0).output()).isEqualTo("rewritten=q");

        assertThat(rows.get(1).stepOrder()).isEqualTo(2);
        assertThat(rows.get(1).stepName()).isEqualTo("execute");
        assertThat(rows.get(1).spanType()).isEqualTo("sql");
        assertThat(rows.get(1).output()).isEqualTo("rows=3");
        assertThat(rows.get(1).latencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(t.spanCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("span 里的异常：记录下来再原样抛出，不吞异常")
    void bodyExceptionIsRecordedAndRethrown() {
        TraceStep t = trace();

        assertThatThrownBy(() -> t.span(TraceSpan.IR_TO_SQL, "mode=AGG", () -> {
            throw new IllegalArgumentException("不支持的排序字段");
        }, Object::toString)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的排序字段");

        t.finish(ctx("q"), RunStatus.FAILED);
        List<AgentTraceRepository.StepRow> rows = flushedSteps();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo("FAILED");
        assertThat(rows.get(0).errorMsg()).contains("不支持的排序字段");
        assertThat(rows.get(0).output()).isNull();
    }

    @Test
    @DisplayName("只有 LLM span 带 token 用量，且是 body 跑完后才读（延迟取值）")
    void tokenUsageComesFromSupplierAfterBody() {
        TraceStep t = trace();
        int[] tokens = {0};

        t.span(TraceSpan.LLM_GENERATE, "question→IR", () -> tokens[0], () -> {
            tokens[0] = 600;   // 模拟 LLM 调用后才拿到用量
            return "mode=AGG";
        }, s -> s);
        t.finish(ctx("q"), RunStatus.SUCCESS);

        List<AgentTraceRepository.StepRow> rows = flushedSteps();
        assertThat(rows.get(0).tokenUsed()).isEqualTo(600);
        assertThat(rows.get(0).spanType()).isEqualTo("llm");
    }

    @Test
    @DisplayName("超长内容按列宽截断：question/error 500，span input/output 2000")
    void overlongValuesAreClipped() {
        TraceStep t = trace();
        String huge = "x".repeat(5000);

        t.span(TraceSpan.PROMPT, huge, () -> huge, s -> s);
        AgentContext c = ctx(huge);
        c.setErrorMessage(huge);
        t.finish(c, RunStatus.FAILED);

        List<AgentTraceRepository.StepRow> rows = flushedSteps();
        assertThat(rows.get(0).input()).hasSize(TraceStep.FIELD_MAX);
        assertThat(rows.get(0).output()).hasSize(TraceStep.FIELD_MAX);

        AgentTraceRepository.RunRow run = flushedRun();
        assertThat(run.question()).hasSize(TraceStep.QUESTION_MAX);
        assertThat(run.errorMsg()).hasSize(TraceStep.ERROR_MAX);
    }

    @Test
    @DisplayName("埋点写库失败不影响问数：span 照常返回结果、finish 不抛异常")
    void repositoryFailureNeverBreaksTheRun() {
        doThrow(new RuntimeException("元数据库连接失败")).when(repo).saveTrace(anyList(), any());
        TraceStep t = trace();

        assertThatCode(() -> {
            String out = t.span(TraceSpan.RECALL, null, () -> "tables=[ads_trade_overview_di]", s -> s);
            assertThat(out).isEqualTo("tables=[ads_trade_overview_di]");
            t.finish(new AgentContext(), RunStatus.SUCCESS);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("span 只攒内存不写库：没调 finish 前一次 I/O 都没有（run 中崩掉也不会拖累问数）")
    void spansAreBufferedUntilFinish() {
        TraceStep t = trace();
        t.span(TraceSpan.REWRITE, "q", () -> "q", s -> s);
        t.span(TraceSpan.RECALL, null, () -> "t", s -> s);

        verify(repo, never()).saveTrace(anyList(), any());
        assertThat(t.spanCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("一次运行只提交一次：9 个 span + run 走一次 saveTrace，不是 10 次往返")
    void allSpansWrittenInOneCommit() {
        TraceStep t = trace();
        for (TraceSpan span : TraceSpan.values()) {
            t.span(span, "in", () -> "out", s -> s);
        }
        t.finish(ctx("q"), RunStatus.SUCCESS);

        List<AgentTraceRepository.StepRow> rows = flushedSteps();
        assertThat(rows).hasSize(TraceSpan.expectedPerSuccess());
        assertThat(rows.get(rows.size() - 1).stepOrder()).isEqualTo(TraceSpan.expectedPerSuccess());
    }

    @Test
    @DisplayName("成本按 datapilot.cost 单价算：500 in + 100 out，默认 1 元/2 元每百万 → 0.0007 元")
    void costIsComputedFromTokenPrices() {
        TraceStep t = trace();
        assertThat(t.costOf(500, 100)).isEqualByComparingTo(new BigDecimal("0.000700"));
        assertThat(t.costOf(0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("收尾写 agent_run：状态/token/成本/最终 SQL/失败原因齐全")
    void finishWritesRunRow() {
        TraceStep t = trace();
        AgentContext c = ctx("10月19日当天的总GMV是多少");
        c.setSessionId("s-1");
        c.setGeneratedSql("SELECT SUM(total_gmv) AS gmv FROM ads_trade_overview_di WHERE dt > ? AND dt <= ?");
        c.setPromptTokens(500);
        c.setCompletionTokens(100);
        c.setTotalTokens(600);

        t.finish(c, RunStatus.SUCCESS);

        AgentTraceRepository.RunRow row = flushedRun();
        assertThat(row.traceId()).isEqualTo("t-0001");
        assertThat(row.sessionId()).isEqualTo("s-1");
        assertThat(row.status()).isEqualTo("SUCCESS");
        assertThat(row.tokens()).isEqualTo(600);
        assertThat(row.cost()).isEqualByComparingTo(new BigDecimal("0.000700"));
        assertThat(row.finalSql()).contains("dt > ? AND dt <= ?");
        assertThat(row.errorMsg()).isNull();
        assertThat(row.startTime()).isNotNull();
        assertThat(row.endTime()).isNotNull();
    }

    @Test
    @DisplayName("describe 为 null 时不写输出摘要（不强行 toString 把对象结构灌进 TEXT 列）")
    void nullDescribeWritesNoOutput() {
        TraceStep t = trace();
        t.span(TraceSpan.GATE_IR, "phase=IR", () -> "whatever", null);
        t.finish(ctx("q"), RunStatus.SUCCESS);

        assertThat(flushedSteps().get(0).output()).isNull();
    }
}
