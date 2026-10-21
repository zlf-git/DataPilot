package com.zlf.datapilot.agent.step;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentStep;
import com.zlf.datapilot.knowledge.model.FewShotExample;
import com.zlf.datapilot.knowledge.model.MetricDef;
import com.zlf.datapilot.knowledge.model.RecalledMetadata;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * ③ Prompt 组装节点：把召回到的 Ontology（指标口径 + 表结构 + few-shot）注入 prompt。
 *
 * <p>Ticket 03 起补充两块约束：
 * <ul>
 *   <li><b>selectMode 语义</b>：AGG（有指标）vs DETAIL（无指标 + 维度），否则 LLM 分不清
 *       「哪些商品」和「多少个商品」；</li>
 *   <li><b>日期归一化</b>（Ticket 03 任务 8）：用户只说「10月19日」时必须补年份，
 *       说「今天/最近」时必须落到数据最新日而不是系统当前日期，否则 Q1/Q3/Q6/Q7 的
 *       日期根本变不成 dt 值。</li>
 * </ul>
 */
@Component
public class PromptBuildStep implements AgentStep {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

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

        ctx.setSystemPrompt(buildSystemPrompt());
        ctx.setPrompt(buildUserPrompt(question, meta));
        return ctx;
    }

    private String buildSystemPrompt() {
        LocalDate today = LocalDate.now(ZONE);
        return """
                你是 DataPilot 数据查询助手。根据用户问题输出结构化查询意图（IR），不要输出 SQL，不要编造表名和字段名。

                今天是 %s（年份 %d）。

                ## 输出结构
                - selectMode：AGG（问金额/数量/比例/最高/最低这类聚合值）或 DETAIL（问「有哪些」「列出」这类明细）
                - metrics：指标 metricId 数组。AGG 必填；DETAIL 必须为空
                - dimensions：维度字段数组。DETAIL 必填；AGG 里非空表示「按该维度分组」
                - filters：{field, op, value}，op 只能是 EQ/IN/LIKE/GT/GE/LT/LE（禁止 BETWEEN）
                - orderBy：{field, direction}，field 可写 metricId（按聚合值排序）或维度字段名
                - limit：只要 TopN 时才给整数，其余留空
                - ratio：只有「A 占 B 的比例」这类占比问题才填 {numerator, denominator}，否则留空

                ## 日期归一化（必须遵守）
                1. 只说「10月19日」没写年份 → 补成今年 %d，输出 yyyy-MM-dd
                2. 「今天/昨天/最近/近7天」没落到具体日期 → 以数据最新日为准，不要用系统当前日期
                3. 所有 dt 过滤值都输出完整 yyyy-MM-dd 字符串
                """.formatted(today, today.getYear(), today.getYear());
    }

    private String buildUserPrompt(String question, RecalledMetadata meta) {
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
            if (meta.getFewShots() != null && !meta.getFewShots().isEmpty()) {
                user.append("参考示例（问题 → IR 要点）：\n");
                for (FewShotExample fs : meta.getFewShots()) {
                    user.append("- 问题：").append(fs.getQuestion())
                            .append(" → ").append(fs.getSql()).append("\n");
                }
            }
        }
        return user.toString();
    }
}
