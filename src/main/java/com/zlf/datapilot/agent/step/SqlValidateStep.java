package com.zlf.datapilot.agent.step;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentStep;
import org.springframework.stereotype.Component;

/**
 * ④ 校验节点（五道闸门占位）。
 * Ticket 01 空实现直接通过；Ticket 02 在此接入存在性/权限/代价/语法闸门。
 */
@Component
public class SqlValidateStep implements AgentStep {

    @Override
    public String name() {
        return "validate";
    }

    @Override
    public AgentContext execute(AgentContext ctx) {
        // Ticket 01 闸门空实现：直接放行（安全兜底由 Ticket 02 落地）
        return ctx;
    }
}
