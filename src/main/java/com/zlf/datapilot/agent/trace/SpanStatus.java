package com.zlf.datapilot.agent.trace;

/**
 * 单个 span 的状态，落库进 {@code agent_step.status}（VARCHAR(16)）。
 * 与 {@link RunStatus} 分开：span 有「跳过/重规划」，run 只有四种终态。
 */
public enum SpanStatus {

    SUCCESS,
    FAILED,

    /** 未执行到（本票不落 SKIPPED 行，留给 Ticket 12 的 HITL 分支） */
    SKIPPED,

    /**
     * 只校验未执行（Ticket 12）：{@code hitl_preview} 专用。
     *
     * <p>它不是 {@code span_type}，是<b>状态</b> —— 票面写得很明确「PREVIEW 是 status 不是 span_type」。
     * 落成 SUCCESS 会让「执行过的步骤数」虚高（预览这一步一条 SQL 都没发），
     * 落成 FAILED 又会把它算进错误率，所以单独一个值。
     */
    PREVIEW,

    /** 闸门要求重规划（Ticket 12 起真正启用：预览后的编辑越界，需回去重新问） */
    REPLAN;

    public static SpanStatus of(boolean failed) {
        return failed ? FAILED : SUCCESS;
    }
}
