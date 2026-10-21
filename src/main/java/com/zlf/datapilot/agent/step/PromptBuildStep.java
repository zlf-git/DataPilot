package com.zlf.datapilot.agent.step;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentStep;
import com.zlf.datapilot.knowledge.model.MetricDef;
import com.zlf.datapilot.knowledge.model.RecalledMetadata;
import org.springframework.stereotype.Component;

/**
 * ③ Prompt 组装节点：把召回到的 Ontology（指标口径 + 表结构）注入 system/user prompt。
 * Ticket 01 拼基础 prompt；完整 Ontology 全量注入由 F-03 Prompt 模板细化。
 */
@Component
public class PromptBuildStep implements AgentStep {

    private static final String SYSTEM_PROMPT = """
            你是数据查询意图识别助手。根据用户问题，识别要查询的指标（metricId）与过滤条件（field/op/value）。
            只输出结构化 JSON（含 question/metrics/filters），不要输出 SQL，不要编造表名和字段名。
            """;

    @Override
    public String name() {
        return "prompt";
    }

    @Override
    public AgentContext execute(AgentContext ctx) {
        String question = ctx.getRewrittenQuestion() != null
                ? ctx.getRewrittenQuestion()
                : ctx.getUserQuestion();
        RecalledMetadata meta = ctx.getRecalledMetadata();

        StringBuilder user = new StringBuilder();
        user.append("问题：").append(question).append("\n");

        if (meta != null) {
            if (meta.getMetrics() != null && !meta.getMetrics().isEmpty()) {
                user.append("可用指标：\n");
                for (MetricDef m : meta.getMetrics()) {
                    user.append("- ").append(m.getMetricId())
                            .append("(").append(m.getMetricName()).append(")")
                            .append(" 口径=").append(m.getMetricSql())
                            .append(" 表=").append(m.getTableName()).append("\n");
                }
            }
            if (meta.getDdlMap() != null && !meta.getDdlMap().isEmpty()) {
                user.append("表结构：\n");
                meta.getDdlMap().forEach((t, ddl) -> user.append(ddl).append("\n"));
            }
        }

        ctx.setSystemPrompt(SYSTEM_PROMPT);
        ctx.setPrompt(user.toString());
        return ctx;
    }
}
