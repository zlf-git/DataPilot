package com.zlf.datapilot.agent.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * span 目录不变量（Ticket 09）。
 * 目录是「一次成功运行该有多少 span」的唯一真源，它自己错了会让 e2e 的基线一起错。
 */
class TraceSpanTest {

    @Test
    @DisplayName("基线由目录长度决定，不写死数字（01 阶段 6 个节点时也不能失效）")
    void baselineComesFromCatalogLength() {
        assertThat(TraceSpan.expectedPerSuccess()).isEqualTo(TraceSpan.values().length);
        assertThat(TraceSpan.expectedPerSuccess()).isGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("step_name 唯一且不超 agent_step.step_name 的 64 列宽")
    void stepNamesAreUniqueAndFitColumn() {
        Set<String> codes = Arrays.stream(TraceSpan.values()).map(TraceSpan::code).collect(Collectors.toSet());
        assertThat(codes).hasSize(TraceSpan.values().length);
        assertThat(codes).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(64));
    }

    @Test
    @DisplayName("span_type 取值不超过 16 列宽，且五类枚举齐全")
    void spanTypesFitColumnAndAreComplete() {
        assertThat(Arrays.stream(SpanType.values()).map(SpanType::code).toList())
                .containsExactly("operation", "llm", "sql", "gate", "hitl");
        for (SpanType t : SpanType.values()) {
            assertThat(t.code().length()).isLessThanOrEqualTo(16);
        }
    }

    @Test
    @DisplayName("目录里必须各至少有一个 llm 与一个 sql span（验收硬要求）")
    void catalogContainsLlmAndSqlSpans() {
        assertThat(Arrays.stream(TraceSpan.values()).filter(s -> s.type() == SpanType.LLM)).isNotEmpty();
        assertThat(Arrays.stream(TraceSpan.values()).filter(s -> s.type() == SpanType.SQL)).isNotEmpty();
    }

    @Test
    @DisplayName("闸门三阶段各一个 span，漏一段就有一层闸门在 trace 里隐身")
    void oneSpanPerGatePhase() {
        assertThat(Arrays.stream(TraceSpan.values()).filter(s -> s.type() == SpanType.GATE).map(TraceSpan::code))
                .containsExactlyInAnyOrder("gate_ir", "gate_sql", "gate_result");
    }
}
