package com.zlf.datapilot.agent.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentStep;
import com.zlf.datapilot.ir.Filter;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.ir.TimeRange;
import com.zlf.datapilot.knowledge.MetadataRepository;
import com.zlf.datapilot.knowledge.model.MetricDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⑦ 报告节点（Ticket 11 建、Ticket 18 重做）：把 rows 渲染成一份自带图表的 HTML 报告。
 *
 * <p>判据仍是「reportHtml 含 echarts 且 series 长度 = rows 长度」（审计 P1#9 把「非空 html」这条废了），
 * 所以硬约束不变：<b>每行数据恰好对应一条 series</b>。
 *
 * <p><b>Ticket 18 修的两个问题</b>：
 * <ol>
 *   <li><b>图表永远空白</b>：Ticket 11 把 ECharts 挂在 jsdelivr CDN 上。本机（及任何内网/演示环境）
 *       连不上 CDN，{@code window.echarts} 恒为 undefined，报告就只剩一块 380px 空白 + 一张表。
 *       现在改为<b>同源本地静态资源</b> {@code /report-assets/echarts.min.js}（5.5.0，随包发布，
 *       不再依赖外网）。离线环境也能画。</li>
 *   <li><b>单指标报告只有一个数</b>：{@code chartType=metric}（单行单列）原本被当成柱状图画，
 *       一根柱 + 一个数，看起来像报告坏了。现在 metric 渲染成<b>大数字卡片</b>，
 *       并把「口径 / 数据源 / 过滤条件 / 结果规模 / 闸门结论」补进报告，让读者知道这个数怎么来的。</li>
 * </ol>
 *
 * <p>option 用 Jackson 序列化后再嵌进页面，不手拼字符串：值里带引号/换行时手拼会拼出坏 JS，
 * 而这种坏页面在浏览器里表现为「白屏」，排查成本远高于一次序列化。
 */
@Component
public class ReportStep implements AgentStep {

    private static final Logger log = LoggerFactory.getLogger(ReportStep.class);

    /**
     * 同源本地资源（{@code src/main/resources/static/report-assets/echarts.min.js}）。
     * 不用 CDN：jsdelivr 在本机直接 SSL 失败（curl exit 35），图表区会永久空白（Ticket 18 实测）。
     */
    private static final String ECHARTS_LOCAL = "/report-assets/echarts.min.js";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String CSS = String.join("\n",
            "body{font-family:-apple-system,'Microsoft YaHei',sans-serif;margin:24px;color:#1f2329;background:#fff}",
            "h3{margin:0 0 8px;font-size:17px}",
            "h4{margin:22px 0 8px;font-size:14px;color:#1f2329;border-left:3px solid #3370ff;padding-left:8px}",
            ".meta{color:#5c6570;font-size:12px;margin-bottom:14px}",
            "#chart{width:100%;height:380px}",
            ".cards{display:flex;flex-wrap:wrap;gap:14px;margin:6px 0 4px}",
            ".card{flex:1 1 180px;min-width:160px;border:1px solid #e5e8ef;border-radius:10px;",
            "background:linear-gradient(180deg,#f7f9ff 0%,#fff 100%);padding:18px 18px 14px}",
            ".card .v{font-size:34px;font-weight:700;color:#1f2329;line-height:1.15;word-break:break-all}",
            ".card .k{font-size:12px;color:#5c6570;margin-top:6px}",
            "table{border-collapse:collapse;font-size:13px;width:100%}",
            "th,td{border:1px solid #dcdfe6;padding:6px 10px;text-align:left}",
            "th{background:#f5f7fa}",
            "table.kv{width:auto;min-width:60%}",
            "table.kv th{width:92px;white-space:nowrap;font-weight:600}",
            "pre{background:#f7f8fa;border:1px solid #e5e8ef;border-radius:6px;padding:10px 12px;",
            "font-size:12px;overflow:auto;white-space:pre-wrap}",
            "details{margin-top:8px}",
            "summary{cursor:pointer;color:#3370ff;font-size:13px}",
            ".empty{color:#8a919f;font-size:13px;padding:12px 0}");

    private final ObjectMapper objectMapper;
    private final MetadataRepository metadata;

    public ReportStep(ObjectMapper objectMapper, MetadataRepository metadata) {
        this.objectMapper = objectMapper;
        this.metadata = metadata;
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
        String chartType = ctx.getChartType() == null ? "table" : ctx.getChartType();
        String optionJson = toJson(buildOption(ctx, rows));

        StringBuilder html = new StringBuilder(3072);
        html.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<title>DataPilot 问数报告</title>\n")
                .append("<script src=\"").append(ECHARTS_LOCAL).append("\"></script>\n")
                .append("<style>\n").append(CSS).append("\n</style>\n</head>\n<body>\n");

        html.append("<h3>").append(escapeHtml(title(ctx))).append("</h3>\n")
                .append("<div class=\"meta\">traceId ").append(escapeHtml(ctx.getTraceId()))
                .append(" · 图表类型 ").append(escapeHtml(chartTypeLabel(chartType)))
                .append(" · 生成于 ").append(LocalDateTime.now().format(TS)).append("</div>\n");

        // 单指标（单行单列）：不画图，直接给大数字卡片 —— 一根柱子上顶一个数没有信息量
        if ("metric".equals(chartType)) {
            html.append(metricCards(rows));
        } else {
            html.append("<div id=\"chart\"></div>\n");
        }

        html.append("<h4>口径与查询条件</h4>\n").append(conditionTable(ctx, rows));
        html.append("<h4>结果明细</h4>\n").append(tableHtml(rows));
        html.append(sqlBlock(ctx));

        html.append("<script>\nvar option = ").append(optionJson).append(";\n")
                .append("var el = document.getElementById('chart');\n")
                .append("if (window.echarts && el) { echarts.init(el).setOption(option); }\n")
                .append("</script>\n</body>\n</html>\n");
        return html.toString();
    }

    private String title(AgentContext ctx) {
        return ctx.getRewrittenQuestion() == null ? ctx.getUserQuestion() : ctx.getRewrittenQuestion();
    }

    /** 中文名 + 英文标识：英文串用于和 agent_run / trace 里的 chartType 对照排障 */
    private static String chartTypeLabel(String chartType) {
        return switch (chartType) {
            case "metric" -> "单指标（metric）· 大数字卡片";
            case "line" -> "折线图（line）";
            case "bar" -> "柱状图（bar）";
            default -> "明细表（table）";
        };
    }

    /** 大数字卡片：每个数值列一张（metric 场景通常只有一张） */
    private String metricCards(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "<div class=\"empty\">查询无数据</div>\n";
        }
        StringBuilder sb = new StringBuilder("<div class=\"cards\">");
        for (Map.Entry<String, Object> e : rows.get(0).entrySet()) {
            sb.append("<div class=\"card\"><div class=\"v\">").append(escapeHtml(plain(e.getValue())))
                    .append("</div><div class=\"k\">").append(escapeHtml(e.getKey())).append("</div></div>");
        }
        return sb.append("</div>\n").toString();
    }

    /**
     * 口径与条件：让读者知道「这个数是怎么算出来的」。
     *
     * <p>指标中文名与口径表达式从 {@code metric_def} 取（IR 里只有 metricId）；
     * 取不到就只显示 metricId —— 报告是旁路产物，不能因为元数据缺失整份报告失败。
     */
    private String conditionTable(AgentContext ctx, List<Map<String, Object>> rows) {
        QueryIr ir = ctx.getIr();
        StringBuilder sb = new StringBuilder("<table class=\"kv\">\n");

        if (ir != null && !ir.getMetrics().isEmpty()) {
            List<String> names = new ArrayList<>();
            List<String> sqls = new ArrayList<>();
            List<String> tables = new ArrayList<>();
            for (MetricRef ref : ir.getMetrics()) {
                String id = ref.getMetricId();
                MetricDef def = safeMetric(id);
                names.add(def == null ? id : def.getMetricName() + "（" + id + "）");
                if (def != null) {
                    sqls.add(def.getMetricSql());
                    if (def.getTableName() != null) {
                        tables.add(def.getTableName());
                    }
                }
            }
            row(sb, "指标", String.join("、", names));
            if (!sqls.isEmpty()) {
                row(sb, "口径", String.join("；", sqls));
            }
            if (!tables.isEmpty()) {
                row(sb, "数据源", String.join("、", tables));
            }
        }

        row(sb, "过滤条件", conditions(ir));
        String window = timeWindow(ir);
        if (window != null) {
            row(sb, "时间窗口", window);
        }
        row(sb, "结果规模", rows.size() + " 行 × " + (rows.isEmpty() ? 0 : rows.get(0).size()) + " 列");
        if (ctx.getGateResults() != null && !ctx.getGateResults().isEmpty()) {
            row(sb, "闸门结论", String.join(" · ", ctx.getGateResults()));
        }
        return sb.append("</table>\n").toString();
    }

    private static void row(StringBuilder sb, String key, String value) {
        sb.append("<tr><th>").append(escapeHtml(key)).append("</th><td>")
                .append(escapeHtml(value == null ? "—" : value)).append("</td></tr>\n");
    }

    private static String conditions(QueryIr ir) {
        if (ir == null || ir.getFilters().isEmpty()) {
            return "无";
        }
        List<String> parts = new ArrayList<>();
        for (Filter f : ir.getFilters()) {
            parts.add(f.getField() + " " + opLabel(f) + " " + plain(f.getValue()));
        }
        return String.join("；", parts);
    }

    private static String opLabel(Filter f) {
        if (f.getOp() == null) {
            return "=";
        }
        return switch (f.getOp()) {
            case EQ -> "=";
            case IN -> "in";
            case LIKE -> "like";
            case GT -> ">";
            case GE -> ">=";
            case LT -> "<";
            case LE -> "<=";
        };
    }

    private static String timeWindow(QueryIr ir) {
        if (ir == null || ir.getTimeRange() == null) {
            return null;
        }
        TimeRange tr = ir.getTimeRange();
        String w = tr.getRelativeWindow() != null ? tr.getRelativeWindow()
                : (tr.getDays() == null ? "相对窗口" : "近 " + tr.getDays() + " 天");
        return tr.getAnchor() == null ? w : w + "（数据锚点 " + tr.getAnchor() + "）";
    }

    private MetricDef safeMetric(String metricId) {
        if (metricId == null || metadata == null) {
            return null;
        }
        try {
            return metadata.findByMetricId(metricId);
        } catch (RuntimeException e) {
            log.debug("报告取指标口径失败（忽略）：{} {}", metricId, e.getMessage());
            return null;
        }
    }

    /** SQL 折叠区：报告要能自证「这个数来自哪条 SQL」 */
    private String sqlBlock(AgentContext ctx) {
        String sql = ctx.getGeneratedSql();
        if (sql == null || sql.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<h4>生成的 SQL</h4>\n<details>\n")
                .append("<summary>展开（参数化，值不内联）</summary>\n<pre>")
                .append(escapeHtml(sql)).append("</pre>\n");
        List<Object> params = ctx.getSqlCommand() == null ? List.of() : ctx.getSqlCommand().params();
        if (!params.isEmpty()) {
            sb.append("<div class=\"meta\">绑定参数：").append(escapeHtml(plain(params))).append("</div>\n");
        }
        return sb.append("</details>\n").toString();
    }

    /**
     * ECharts option：<b>每行一条 series</b>。
     * x 轴是列名（除第一列维度），series 名取该行第一列的值 —— 这样 3 行数据就是 3 条 series，
     * 与判据「series 长度 = rows 长度」一致。
     *
     * <p>不设 option.title：页面顶部 h3 已经有一份问句，图里再画一次是重复。
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

        option.put("tooltip", Map.of());
        option.put("legend", Map.of("bottom", 0));
        option.put("grid", Map.of("left", 48, "right", 24, "top", 24, "bottom", 56));
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

    /** 图表之外再给一张表：values 一眼可核，也避免「脚本没跑起来就什么都看不到」 */
    private String tableHtml(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "<div class=\"empty\">查询无数据</div>\n";
        }
        StringBuilder sb = new StringBuilder("<table>\n<thead><tr>");
        rows.get(0).keySet().forEach(c -> sb.append("<th>").append(escapeHtml(c)).append("</th>"));
        sb.append("</tr></thead>\n<tbody>\n");
        for (Map<String, Object> row : rows) {
            sb.append("<tr>");
            row.values().forEach(v -> sb.append("<td>").append(escapeHtml(plain(v))).append("</td>"));
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

    /** 数值原样输出（BigDecimal 不用科学计数法，1:1 可核） */
    private static String plain(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        return String.valueOf(v);
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
