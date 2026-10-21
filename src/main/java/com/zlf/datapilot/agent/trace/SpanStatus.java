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

    /** 闸门要求重规划（保留值，供后续版本使用） */
    REPLAN;

    public static SpanStatus of(boolean failed) {
        return failed ? FAILED : SUCCESS;
    }
}
