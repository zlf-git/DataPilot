package com.zlf.datapilot.agent.step;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentStep;
import com.zlf.datapilot.knowledge.Recaller;
import com.zlf.datapilot.knowledge.model.RecalledMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * ② 元数据召回节点：找跟问题相关的表、字段、指标、术语、few-shot。
 */
@Component
public class RecallStep implements AgentStep {

    private final Recaller recaller;

    public RecallStep(Recaller recaller) {
        this.recaller = recaller;
    }

    @Override
    public String name() {
        return "recall";
    }

    @Override
    public AgentContext execute(AgentContext ctx) {
        String question = ctx.getRewrittenQuestion() != null
                ? ctx.getRewrittenQuestion()
                : ctx.getUserQuestion();
        RecalledMetadata meta = recaller.recall(question);
        ctx.setRecalledMetadata(meta);
        ctx.setRelatedTables(new ArrayList<>(meta.getDdlMap().keySet()));
        return ctx;
    }
}
