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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 埋点器单测（Ticket 09）。不连库：agent_step/agent_run 的写入用 mock 仓储捕获，
 * 断言的是「落库的内容与顺序」而不是「SQL 能不能执行」——后者由 e2e（qa/verify_traces.py）在真库上验。
 */
class TraceStepTest {

    private final AgentTraceRepository repo = mock(AgentTraceRepository.class);
    private final DatapilotProperties props = new DatapilotProperties();

    private TraceStep trace() {
        return new TraceStep(repo, props.getCost(), "t-0001");
    }

    private List<AgentTraceRepository.StepRow> captureSteps(int expected) {
        ArgumentCaptor<AgentTraceRepository.StepRow> cap = ArgumentCaptor.forClass(AgentTraceRepository.StepRow.class);
        verify(repo, times(expected)).saveStep(cap.capture());
        return cap.getAllValues();
    }

    @Test
    @DisplayName("span 按执行顺序编号，step_name/span_type 取自 span 目录")
    void spansAreOrderedAndTyped() {
        TraceStep t = trace();
        t.span(TraceSpan.REWRITE, "q", () -> "q", s -> "rewritten=" + s);
        t.span(TraceSpan.EXECUTE, "select 1", () -> 3, n -> "rows=" + n);

        List<AgentTraceRepository.StepRow> rows = captureSteps(2);
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
    @DisplayName("span 里的异常：先落库 FAILED 再原样抛出，不吞异常")
    void bodyExceptionIsRecordedAndRethrown() {
        TraceStep t = trace();

        assertThatThrownBy(() -> t.span(TraceSpan.IR_TO_SQL, "mode=AGG", () -> {
            throw new IllegalArgumentException("不支持的排序字段");
        }, Object::toString)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的排序字段");

        List<AgentTraceRepository.StepRow> rows = captureSteps(1);
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

        List<AgentTraceRepository.StepRow> rows = captureSteps(1);
        assertThat(rows.get(0).tokenUsed()).isEqualTo(600);
        assertThat(rows.get(0).spanType()).isEqualTo("llm");
    }

    @Test
    @DisplayName("超长内容按列宽截断：question/error 500，span input/output 2000")
    void overlongValuesAreClipped() {
        TraceStep t = trace();
        String huge = "x".repeat(5000);

        t.span(TraceSpan.PROMPT, huge, () -> huge, s -> s);

        AgentContext ctx = new AgentContext();
        ctx.setUserQuestion(huge);
        ctx.setErrorMessage(huge);
        t.finish(ctx, RunStatus.FAILED);

        List<AgentTraceRepository.StepRow> rows = captureSteps(1);
        assertThat(rows.get(0).input()).hasSize(TraceStep.FIELD_MAX);
        assertThat(rows.get(0).output()).hasSize(TraceStep.FIELD_MAX);

        ArgumentCaptor<AgentTraceRepository.RunRow> runCap = ArgumentCaptor.forClass(AgentTraceRepository.RunRow.class);
        verify(repo).saveRun(runCap.capture());
        assertThat(runCap.getValue().question()).hasSize(TraceStep.QUESTION_MAX);
        assertThat(runCap.getValue().errorMsg()).hasSize(TraceStep.ERROR_MAX);
    }

    @Test
    @DisplayName("埋点写库失败不影响问数：span 照常返回结果、finish 不抛异常")
    void repositoryFailureNeverBreaksTheRun() {
        doThrow(new RuntimeException("元数据库连接失败")).when(repo).saveStep(any());
        doThrow(new RuntimeException("元数据库连接失败")).when(repo).saveRun(any());
        TraceStep t = trace();

        assertThatCode(() -> {
            String out = t.span(TraceSpan.RECALL, null, () -> "tables=[ads_trade_overview_di]", s -> s);
            assertThat(out).isEqualTo("tables=[ads_trade_overview_di]");
            t.finish(new AgentContext(), RunStatus.SUCCESS);
        }).doesNotThrowAnyException();
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
        AgentContext ctx = new AgentContext();
        ctx.setSessionId("s-1");
        ctx.setUserQuestion("10月19日当天的总GMV是多少");
        ctx.setGeneratedSql("SELECT SUM(total_gmv) AS gmv FROM ads_trade_overview_di WHERE dt > ? AND dt <= ?");
        ctx.setPromptTokens(500);
        ctx.setCompletionTokens(100);
        ctx.setTotalTokens(600);

        t.finish(ctx, RunStatus.SUCCESS);

        ArgumentCaptor<AgentTraceRepository.RunRow> cap = ArgumentCaptor.forClass(AgentTraceRepository.RunRow.class);
        verify(repo).saveRun(cap.capture());
        AgentTraceRepository.RunRow row = cap.getValue();
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

        assertThat(captureSteps(1).get(0).output()).isNull();
    }

    @Test
    @DisplayName("仓储只在 span 结束时写一次（异常路径不重复写）")
    void exactlyOneStepRowPerSpanEvenOnFailure() {
        TraceStep t = trace();

        assertThatThrownBy(() -> t.span(TraceSpan.EXECUTE, "select 1", () -> {
            throw new IllegalStateException("boom");
        }, Object::toString)).isInstanceOf(IllegalStateException.class);

        captureSteps(1);
    }
}
