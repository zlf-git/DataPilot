package com.zlf.datapilot.agent.trace;

import com.zlf.datapilot.agent.AgentContext;

/**
 * 单次问数的运行状态，落库进 {@code agent_run.status}（VARCHAR(16)）。
 *
 * <p>三态区分是刻意的：把「闸门拦下」和「跑挂了」混成一个 FAILED，
 * 就看不出「拦截率」这个真正该盯的指标（问数被拦是策略生效，不是系统故障）。
 */
public enum RunStatus {

    /** 走完全链路并给出结果（空结果也算成功，属于数据事实不是故障） */
    SUCCESS,

    /** 链路中途出错：LLM 失败/超时、IR 无法识别、SQL 执行失败 */
    FAILED,

    /** 被五道闸门拦下（属于策略生效，不是故障） */
    REJECTED,

    /** 只生成预览未执行（Ticket 12 HITL：四道闸门全过，但一条 SQL 都没跑） */
    PREVIEW;

    /**
     * 判断口径（唯一实现，别在引擎里另写一套）：
     * <ol>
     *   <li>有 errorMessage 且任意闸门结果是 {@code xxx=REJECT} → {@link #REJECTED}；</li>
     *   <li>有 errorMessage → {@link #FAILED}；</li>
     *   <li>否则 {@link #SUCCESS}。</li>
     * </ol>
     * 为什么从 gateResults 反查而不是给 AgentContext 加字段：闸门结果本来就是链路透明回显的一部分，
     * 已有信息能判就不新增状态位，少一个「谁都能写」的字段（评审反复强调的写入方唯一原则）。
     *
     * <p>Ticket 12 破了这个规矩一次：HITL 的两条路径（预览、编辑越界）推导不出来，
     * 只能由引擎显式写 {@code ctx.runStatus}。约束是<b>写入方唯一</b>——只有
     * {@code AgentEngine.preview/executePreview} 写它，其余路径恒为 null，行为不变。
     */
    public static RunStatus of(AgentContext ctx) {
        if (ctx.getRunStatus() != null) {
            return ctx.getRunStatus();
        }
        boolean hasError = ctx.getErrorMessage() != null && !ctx.getErrorMessage().isBlank();
        if (!hasError) {
            return SUCCESS;
        }
        boolean rejected = ctx.getGateResults() != null
                && ctx.getGateResults().stream().anyMatch(TraceSpanSupport::isReject);
        return rejected ? REJECTED : FAILED;
    }

    /** 闸门结果回显的格式解析（GateChain 写的是「闸门名=PASS」/「闸门名=REJECT」/「…=PASS(WARN)」） */
    static final class TraceSpanSupport {

        private TraceSpanSupport() {
        }

        static boolean isReject(String gateResult) {
            return gateResult != null && gateResult.contains("=REJECT");
        }
    }
}
