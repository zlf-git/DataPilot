package com.zlf.datapilot.agent.gate;

import com.zlf.datapilot.agent.AgentContext;

/**
 * 闸门接口：五道闸门的统一抽象。
 * 每个闸门只做一件事，输入输出走 AgentContext（ir / sqlCommand / queryResult 按阶段就绪）。
 * 任一闸门返回不通过即终止链路，返回拒绝原因。
 *
 * <p>五道闸门按执行顺序分三个阶段：
 * IR 层（①存在性 → ②权限 → ③代价）→ SQL 层（④语法）→ 结果层（⑤结果自检）。
 */
public interface Gate {

    /** 闸门名（日志 + 链路回显） */
    String name();

    /** 所属阶段：IR 层 / SQL 层 / 结果层 */
    Phase phase();

    /** 检查，不通过返回 reject（含原因），通过返回 ok；结果自检通过但可疑时返回 ok + warning */
    GateResult check(AgentContext ctx);

    enum Phase {
        /** IR 层：存在性/权限/代价（IR 转 SQL 之前） */
        IR,
        /** SQL 层：语法（IR 转 SQL 之后、执行之前） */
        SQL,
        /** 结果层：结果自检（执行之后） */
        RESULT
    }
}
