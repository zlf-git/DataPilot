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
                  op=IN 时 value 必须是 JSON 数组（如 ["APP","小程序"]），不要写逗号串
                - timeRange：只用于「近 N 天 / 最近 N 天」这类相对窗口，格式 {relativeWindow:"LAST_N_DAYS", days:N}。
                  relativeWindow 必须显式给出（缺了直接拒绝，不会替你猜成「近 N 天」），anchor 不要填。
                  具体起止日期由系统按数据最新日推算，你不要自己算日期、不要填 anchor，
                  也不要在 filters 里再写 dt 条件（两者只能二选一）
                - orderBy：{field, direction}，field 可写 metricId（按聚合值排序）或维度字段名
                - limit：只要 TopN 时才给整数，其余留空
                - ratio：**已停用，不要填**。「A 占 B 的比例」这类问题请直接选指标清单里带「占比」的复合指标
                  （如 gmv_channel_ratio），分子分母与跨表口径都在指标定义里，你不要自己拆

                ## 日期归一化（必须遵守）
                1. 只说「10月19日」没写年份 → 补成今年 %d，输出 yyyy-MM-dd，放进 filters 的 dt EQ 条件
                2. 「今天/昨天/最近/近7天」没落到具体日期 → 用 timeRange（LAST_N_DAYS + days），不要用系统当前日期硬算
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
                            .append(" 口径=").append(renderCaliber(m))
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

    /**
     * 渲染指标口径（Ticket 05）：跨表占比指标的 metric_sql 只有分子（如 SUM(gmv)），
     * 直接平铺会让 LLM 以为它就是普通渠道 GMV，从而在「占比」问题上选错指标。
     * 这里把分母表与分母口径一并写出来，让「占比」语义在指标清单里可见。
     */
    private String renderCaliber(MetricDef m) {
        String sql = m.getMetricSql();
        String denSql = m.getDenominatorSql();
        if (denSql == null || denSql.isBlank()) {
            return sql;
        }
        String denTable = m.getDenominatorTable();
        String where = (denTable == null || denTable.isBlank()) ? "" : "（同时间 " + denTable + "）";
        return sql + " ÷ " + denSql + where + " [复合占比]";
    }
}
