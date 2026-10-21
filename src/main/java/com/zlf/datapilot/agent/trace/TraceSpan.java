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
    REWRITE("rewrite", SpanType.OPERATION, true),

    /** 元数据召回：表/字段/指标/术语/few-shot */
    RECALL("recall", SpanType.OPERATION, true),

    /** 拼 Prompt（Ontology 白名单 + 输出约束） */
    PROMPT("prompt", SpanType.OPERATION, true),

    /** LLM 生成 IR，唯一带 token 用量的 span */
    LLM_GENERATE("llm_generate", SpanType.LLM, true),

    /** 闸门阶段 1：IR 层（存在性 / 权限 / 代价） */
    GATE_IR("gate_ir", SpanType.GATE, true),

    /** IR → 参数化 SQL（不出网、不碰库，所以是 operation 不是 sql） */
    IR_TO_SQL("ir_to_sql", SpanType.OPERATION, true),

    /** 闸门阶段 2：SQL 层（语法 + 表/字段二次比对） */
    GATE_SQL("gate_sql", SpanType.GATE, true),

    /** 真正打到当前引擎上的执行 */
    EXECUTE("execute", SpanType.SQL, true),

    /** 闸门阶段 3：结果层（结果自检，只警告不拒绝） */
    GATE_RESULT("gate_result", SpanType.GATE, true),

    /** 解释（Ticket 11）：把结果说成一句人话 + 定图表类型 */
    EXPLAIN("explain", SpanType.OPERATION, true),

    /** 报告（Ticket 11）：按结果行生成 ECharts HTML */
    REPORT("report", SpanType.OPERATION, true),

    /**
     * 人工确认（Ticket 12）：预览已生成、等用户确认。<b>不进 ask 基线</b> —— 它只在
     * {@code /api/ask/preview} 的 trace 里出现，进基线会把普通问数的「应有多少 span」抬高一格。
     *
     * <p>注意 PREVIEW 是<b>状态</b>（{@link SpanStatus#PREVIEW}），不是 span 名也不是 span_type：
     * 本 span 的 {@code span_type} 恒为 {@code hitl}，状态位描述的是「这次人工介入的性质」。
     */
    HITL_PREVIEW("hitl_preview", SpanType.HITL, false),

    /** 人工确认后的执行（Ticket 12）：状态可能是 SUCCESS 或 REPLAN（编辑越界、需重新问数）；同样不进 ask 基线 */
    HITL_EXECUTE("hitl_execute", SpanType.HITL, false);

    private final String code;
    private final SpanType type;
    private final boolean askBaseline;

    TraceSpan(String code, SpanType type, boolean askBaseline) {
        this.code = code;
        this.type = type;
        this.askBaseline = askBaseline;
    }

    public String code() {
        return code;
    }

    public SpanType type() {
        return type;
    }

    /** 是否属于「一次成功问数」的链路（HITL 两个 span 不属于，见各条目注释） */
    public boolean inAskBaseline() {
        return askBaseline;
    }

    /**
     * 一次完整成功运行应产出的 span 数（验收基线，别写死数字）。
     *
     * <p>Ticket 12 起 <b>不再等于 {@code values().length}</b>：HITL 的 preview/execute 是另一条入口的
     * span，普通问数不会产出它们。过滤条件写在这里、而不是让脚本去减一个常数，
     * 是为了让「加了 span 就该更新基线」这件事没有中间层可出错。
     */
    public static int expectedPerSuccess() {
        return (int) java.util.Arrays.stream(values()).filter(TraceSpan::inAskBaseline).count();
    }
}
