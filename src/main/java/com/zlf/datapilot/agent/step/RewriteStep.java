package com.zlf.datapilot.agent.step;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentStep;
import com.zlf.datapilot.llm.LlmClient;
import org.springframework.stereotype.Component;

/**
 * ① 问题改写节点：把口语化问题规范成明确的数据查询意图（补时间范围/口径/维度）。
 */
@Component
public class RewriteStep implements AgentStep {

    private static final String SYSTEM_PROMPT = """
            你是一个数据查询意图规范化助手。把用户的口语化问题改写成一句规范、完整的数据查询，
            补全缺失的时间范围（如"上月"）、指标口径、统计维度。只输出改写后的问题，不要解释。
            """;

    private final LlmClient llm;

    public RewriteStep(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public String name() {
        return "rewrite";
    }

    @Override
    public AgentContext execute(AgentContext ctx) {
        String content = llm.chat(SYSTEM_PROMPT, "问题：" + ctx.getUserQuestion()).getContent();
        ctx.setRewrittenQuestion(content == null ? ctx.getUserQuestion() : content.trim());
        return ctx;
    }
}
