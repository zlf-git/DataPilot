package com.zlf.datapilot.agent.step;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentStep;
import org.springframework.stereotype.Component;

/**
 * ① 问题改写节点：多轮会话里补全指代/时间（A2 的活）。
 * Ticket 01 骨架阶段暂不实现改写，直接透传原始问题，保持 6 节点编排完整。
 */
@Component
public class RewriteStep implements AgentStep {

    @Override
    public String name() {
        return "rewrite";
    }

    @Override
    public AgentContext execute(AgentContext ctx) {
        // Ticket 01：暂不调 LLM 改写，透传（多轮补全指代由 Ticket 10 实现）
        ctx.setRewrittenQuestion(ctx.getUserQuestion());
        return ctx;
    }
}
