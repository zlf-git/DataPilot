package com.zlf.datapilot.agent.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⑦ 报告节点（Ticket 11）：把 rows 渲染成一份自带图表的 HTML（ECharts）。
 *
 * <p>判据是「reportHtml 含 echarts 且 series 长度 = rows 长度」（审计 P1#9 把「非空 html」这条废了），
 * 所以这里的硬约束是：<b>每行数据恰好对应一条 series</b>。行数变了 series 数必须跟着变，
 * 不是画一张固定形状的图然后把数据塞进去。
 *
 * <p>option 用 Jackson 序列化后再嵌进页面，不手拼字符串：值里带引号/换行时手拼会拼出坏 JS，
 * 而这种坏页面在浏览器里表现为「白屏」，排查成本远高于一次序列化。
 *
 * <p>ECharts 走 CDN（无网时图表区空白、表格仍在），因为把 1MB 的 echarts.min.js 塞进每个
 * 响应体不现实；这是本票明确的降级路径，写在「已知边界」里。
 */
@Component
public class ReportStep implements AgentStep {

    private static final Logger log = LoggerFactory.getLogger(ReportStep.class);

    private static final String ECHARTS_CDN = "https://cdn.jsdelivr.net/npm/echarts@5.5.0/dist/echarts.min.js";

    private final ObjectMapper objectMapper;

    public ReportStep(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "report";
    }

    @Override
    public AgentContext execute(AgentContext ctx) {
        ctx.setReportHtml(render(ctx));
        return ctx;
    }

    private String render(AgentContext ctx) {
        List<Map<String, Object>> rows = ctx.getQueryResult() == null ? List.of() : ctx.getQueryResult();
        String optionJson = toJson(buildOption(ctx, rows));
        String table = tableHtml(rows);
        StringBuilder html = new StringBuilder(1024);
        html.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<title>DataPilot 问数报告</title>\n")
                .append("<script src=\"").append(ECHARTS_CDN).append("\"></script>\n")
                .append("<style>body{font-family:-apple-system,'Microsoft YaHei',sans-serif;margin:24px;color:#1f2329}")
                .append("#chart{width:100%;height:420px}table{border-collapse:collapse;margin-top:16px;font-size:13px}")
                .append("th,td{border:1px solid #dcdfe6;padding:6px 10px;text-align:left}th{background:#f5f7fa}")
                .append(".q{color:#5c6570;font-size:13px}</style>\n</head>\n<body>\n")
                .append("<h3>").append(escapeHtml(ctx.getRewrittenQuestion() == null
                        ? ctx.getUserQuestion() : ctx.getRewrittenQuestion())).append("</h3>\n")
                .append("<div class=\"q\">traceId ").append(escapeHtml(ctx.getTraceId()))
                .append(" · 图表类型 ").append(escapeHtml(ctx.getChartType())).append("</div>\n")
                .append("<div id=\"chart\"></div>\n")
                .append("<div id=\"table\"></div>\n")
                .append(table)
                .append("<script>\nvar option = ").append(optionJson).append(";\n")
                .append("var el = document.getElementById('chart');\n")
                .append("if (window.echarts && el) { echarts.init(el).setOption(option); }\n")
                .append("</script>\n</body>\n</html>\n");
        return html.toString();
    }

    /**
     * ECharts option：<b>每行一条 series</b>。
     * x 轴是列名（除第一列维度），series 名取该行第一列的值 —— 这样 3 行数据就是 3 条 series，
     * 与判据「series 长度 = rows 长度」一致。
     */
    private Map<String, Object> buildOption(AgentContext ctx, List<Map<String, Object>> rows) {
        Map<String, Object> option = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            option.put("title", Map.of("text", "查询无数据", "left", "center"));
            option.put("series", List.of());
            return option;
        }
        List<String> cols = new ArrayList<>(rows.get(0).keySet());
        List<String> valueCols = cols.size() > 1 ? cols.subList(1, cols.size()) : cols;

        option.put("title", Map.of("text",
                ctx.getRewrittenQuestion() == null ? ctx.getUserQuestion() : ctx.getRewrittenQuestion(),
                "left", "center", "textStyle", Map.of("fontSize", 14)));
        option.put("tooltip", Map.of());
        option.put("legend", Map.of("bottom", 0));
        option.put("xAxis", Map.of("type", "category", "data", valueCols));
        option.put("yAxis", Map.of("type", "value"));

        String seriesType = "line".equals(ctx.getChartType()) ? "line" : "bar";
        List<Map<String, Object>> series = new ArrayList<>();
        int i = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> s = new LinkedHashMap<>();
            Object label = cols.isEmpty() ? null : row.get(cols.get(0));
            s.put("name", label == null ? ("第 " + (i + 1) + " 行") : String.valueOf(label));
            s.put("type", seriesType);
            List<Object> data = new ArrayList<>();
            for (String col : valueCols) {
                data.add(row.get(col));
            }
            s.put("data", data);
            series.add(s);
            i++;
        }
        option.put("series", series);
        return option;
    }

    /** 图表之外再给一张表：values 一眼可核，也避免「CDN 拿不到就什么都看不到」 */
    private String tableHtml(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<table>\n<thead><tr>");
        rows.get(0).keySet().forEach(c -> sb.append("<th>").append(escapeHtml(c)).append("</th>"));
        sb.append("</tr></thead>\n<tbody>\n");
        for (Map<String, Object> row : rows) {
            sb.append("<tr>");
            row.values().forEach(v -> sb.append("<td>").append(escapeHtml(v == null ? "" : String.valueOf(v)))
                    .append("</td>"));
            sb.append("</tr>\n");
        }
        return sb.append("</tbody>\n</table>\n").toString();
    }

    private String toJson(Map<String, Object> option) {
        try {
            // 序列化后把 `<` 转成 `\u003c`：JSON 里 `</script>` 会提前闭合脚本块，
            // 后面再想「转义 HTML」已经晚了（浏览器已经把那段当标签解析）。转成 unicode 转义后
            // JSON 语义不变（解析回来还是 `<`），但脚本块不会被截断。
            return objectMapper.writeValueAsString(option).replace("<", "\\u003c");
        } catch (JsonProcessingException e) {
            // 序列化失败不该让整次问数失败：报告是旁路产物，退化成「只有表格」的页面
            log.warn("报告 option 序列化失败，降级为空图：{}", e.getMessage());
            return "{\"series\":[]}";
        }
    }

    private String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
