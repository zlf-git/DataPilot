package com.zlf.datapilot.agent.trace;

/**
 * 全链路 span 目录（Ticket 09）：<b>「一次成功问数会产出哪些 span」的唯一真源</b>。
 *
 * <p>这个枚举存在的理由不是好看，是让「step 数 ≥ 当前装配的 Step 数」这条验收可判：
 * 01 阶段链路只有 6 个节点，写死 8 会让本票当场失败；写成 {@code TraceSpan.values().length}
 * 之后，将来加节点（12 的 HITL、11 的解释）只改两处（枚举 + 引擎），测试与 e2e 脚本自动跟随。
 * 所以 <b>不要在任何地方再写死 span 数量</b>，要拿基线就读 {@code TraceSpan.values().length}
 * 或 {@code GET /api/health/traces}。
 *
 * <p>顺序即执行顺序，{@code agent_step.step_order} 只用「本次运行实际执行到第几个」，
 * 与这里的声明顺序无关（失败会在中途停下，后面的 span 不落库 —— 不写 SKIPPED 行，
 * 避免「一堆 SKIPPED」把 trace 里真正发生的事淹掉；跳过原因看 agent_run.status）。
 */
public enum TraceSpan {

    /** 问题改写（Ticket 01 起是透传，保留 span 是为了将来接真改写时 trace 不出现空洞） */
    REWRITE("rewrite", SpanType.OPERATION),

    /** 元数据召回：表/字段/指标/术语/few-shot */
    RECALL("recall", SpanType.OPERATION),

    /** 拼 Prompt（Ontology 白名单 + 输出约束） */
    PROMPT("prompt", SpanType.OPERATION),

    /** LLM 生成 IR，唯一带 token 用量的 span */
    LLM_GENERATE("llm_generate", SpanType.LLM),

    /** 闸门阶段 1：IR 层（存在性 / 权限 / 代价） */
    GATE_IR("gate_ir", SpanType.GATE),

    /** IR → 参数化 SQL（不出网、不碰库，所以是 operation 不是 sql） */
    IR_TO_SQL("ir_to_sql", SpanType.OPERATION),

    /** 闸门阶段 2：SQL 层（语法 + 表/字段二次比对） */
    GATE_SQL("gate_sql", SpanType.GATE),

    /** 真正打到当前引擎上的执行 */
    EXECUTE("execute", SpanType.SQL),

    /** 闸门阶段 3：结果层（结果自检，只警告不拒绝） */
    GATE_RESULT("gate_result", SpanType.GATE);

    private final String code;
    private final SpanType type;

    TraceSpan(String code, SpanType type) {
        this.code = code;
        this.type = type;
    }

    public String code() {
        return code;
    }

    public SpanType type() {
        return type;
    }

    /** 一次完整成功运行应产出的 span 数（验收基线，别写死数字） */
    public static int expectedPerSuccess() {
        return values().length;
    }
}
