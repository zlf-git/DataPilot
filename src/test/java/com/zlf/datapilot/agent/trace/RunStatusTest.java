package com.zlf.datapilot.agent.trace;

import com.zlf.datapilot.agent.AgentContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行状态判定单测（Ticket 09）。
 * 这条判定的价值在于把「闸门拦住」和「跑挂了」分开：混成一个 FAILED 就看不出拦截率。
 */
class RunStatusTest {

    private AgentContext ctx(String error, String... gates) {
        AgentContext c = new AgentContext();
        c.setErrorMessage(error);
        for (String g : gates) {
            c.addGateResult(g);
        }
        return c;
    }

    @Test
    @DisplayName("无 errorMessage → SUCCESS（空结果也算成功，是数据事实不是故障）")
    void successWhenNoError() {
        assertThat(RunStatus.of(ctx(null, "存在性=PASS", "结果自检=PASS(WARN)"))).isEqualTo(RunStatus.SUCCESS);
    }

    @Test
    @DisplayName("有 errorMessage 且某道闸门 REJECT → REJECTED（策略生效，不是故障）")
    void rejectedWhenGateRejected() {
        assertThat(RunStatus.of(ctx("超出可查询范围", "存在性=PASS", "代价=REJECT"))).isEqualTo(RunStatus.REJECTED);
    }

    @Test
    @DisplayName("有 errorMessage 但闸门全 PASS → FAILED（LLM/执行层失败）")
    void failedWhenGatesAllPassed() {
        assertThat(RunStatus.of(ctx("SQL 执行失败", "存在性=PASS", "语法=PASS"))).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("没有任何闸门结果的失败（空问题、LLM 超时）→ FAILED")
    void failedWhenNoGateRanAtAll() {
        assertThat(RunStatus.of(ctx("模型响应超时，请重试"))).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("空白 errorMessage 视作无错误（避免「空串失败」被记成 FAILED）")
    void blankErrorIsTreatedAsNoError() {
        assertThat(RunStatus.of(ctx("   "))).isEqualTo(RunStatus.SUCCESS);
    }
}
