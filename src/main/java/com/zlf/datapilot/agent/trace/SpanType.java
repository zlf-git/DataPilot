package com.zlf.datapilot.agent.trace;

/**
 * span 类型（Ticket 09）。落库进 {@code agent_step.span_type}，值即 {@link #code()}（小写）。
 *
 * <p>枚举固定五个值，缺一个就意味着「这类环节没进可观测范围」：
 * <ul>
 *   <li>{@link #OPERATION}：编排类节点（改写/召回/拼 Prompt/IR 转 SQL），不产生外部调用；</li>
 *   <li>{@link #LLM}：大模型生成，唯一带 token 用量的类型；</li>
 *   <li>{@link #SQL}：真正打到数据源上的执行（当前引擎可换，见 Ticket 07）；</li>
 *   <li>{@link #GATE}：五道闸门的阶段检查（IR / SQL / 结果三层各一段）；</li>
 *   <li>{@link #HITL}：人工介入（Ticket 12 预览执行两段用，本票只占位不产出）。</li>
 * </ul>
 */
public enum SpanType {

    OPERATION("operation"),
    LLM("llm"),
    SQL("sql"),
    GATE("gate"),
    HITL("hitl");

    private final String code;

    SpanType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    @Override
    public String toString() {
        return code;
    }
}
