package com.zlf.datapilot.agent.step;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentStep;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ⑥ 解释节点（Ticket 11）：把结果说成一句人话，并按结果形状定图表类型。
 *
 * <p>这里是<b>模板生成</b>而不是再调一次 LLM：同一份 rows 让模型自由发挥，
 * 就有机会说出「GMV 环比增长 12%」这种数据里没有的结论（幻觉最贵的一种）。
 * 模板只能说数据里有的东西，代价是句式单一 —— 单机作品里这个取舍是划算的。
 *
 * <p>答案优先级：结果自检的「无数据」/环比的补充结论已经在 ctx 里，<b>不能覆盖</b>；
 * 只有在 answer 还空着时才补一句。图表类型则每轮都重算（空结果同样要有个合理形状）。
 */
@Component
public class ExplainStep implements AgentStep {

    /** 结果行数上限：再多也是「共 N 行」，不必逐行念 */
    private static final int MAX_LISTED_ROWS = 5;

    @Override
    public String name() {
        return "explain";
    }

    @Override
    public AgentContext execute(AgentContext ctx) {
        List<Map<String, Object>> rows = ctx.getQueryResult();
        ctx.setChartType(chartType(rows));
        if (ctx.getAnswer() == null || ctx.getAnswer().isBlank()) {
            ctx.setAnswer(describe(rows));
        }
        return ctx;
    }

    /**
     * 图表类型按形状判定（票面任务 1）：
     * 空 → {@code table}；单行单列 → {@code metric}；多行单列 → {@code bar}；
     * 两列且第一列是日期 → {@code line}；两列其它 → {@code bar}；三列及以上 → {@code table}。
     */
    private String chartType(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "table";
        }
        int cols = columnCount(rows);
        if (rows.size() == 1 && cols == 1) {
            return "metric";
        }
        if (cols == 1) {
            return "bar";
        }
        if (cols == 2) {
            return isDateColumn(rows) ? "line" : "bar";
        }
        return "table";
    }

    private String describe(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        if (rows.size() == 1 && columnCount(rows) == 1) {
            Map.Entry<String, Object> only = rows.get(0).entrySet().iterator().next();
            return only.getKey() + " = " + only.getValue();
        }
        StringBuilder sb = new StringBuilder("共 ").append(rows.size()).append(" 行");
        List<String> names = List.copyOf(rows.get(0).keySet());
        if (names.size() >= 2) {
            sb.append("（").append(names.get(0)).append(" → ").append(names.get(1)).append("）");
        }
        sb.append("：");
        int limit = Math.min(rows.size(), MAX_LISTED_ROWS);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> row = rows.get(i);
            if (i > 0) {
                sb.append("、");
            }
            Object key = row.get(names.get(0));
            Object value = names.size() >= 2 ? row.get(names.get(1)) : null;
            sb.append(key).append(names.size() >= 2 ? "=" + value : "");
        }
        if (rows.size() > limit) {
            sb.append(" 等");
        }
        return sb.toString();
    }

    private int columnCount(List<Map<String, Object>> rows) {
        Map<String, Object> first = rows.get(0);
        return first == null ? 0 : first.size();
    }

    /** 第一列是不是日期：列名叫 dt/date/日期，或取值形如 2026-10-19 */
    private boolean isDateColumn(List<Map<String, Object>> rows) {
        Map<String, Object> first = rows.get(0);
        if (first == null || first.isEmpty()) {
            return false;
        }
        Map.Entry<String, Object> col = first.entrySet().iterator().next();
        String name = col.getKey() == null ? "" : col.getKey().toLowerCase();
        if (name.equals("dt") || name.contains("date") || name.contains("日期") || name.contains("日")) {
            return true;
        }
        Object v = col.getValue();
        return v instanceof String s && s.matches("\\d{4}-\\d{1,2}-\\d{1,2}.*");
    }
}
