package com.zlf.datapilot.agent.gate;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.knowledge.OntologyCache;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 闸门⑤ 结果自检（结果层）：对执行结果做合理性自检，把「无数据/超长结果/异常值」包装成可读反馈。
 * 不拒绝（空结果不是错误），只附加 warning；行数超阈值截断。
 *
 * <p>三个阈值分两类（Ticket 16 / G1）：
 * <ul>
 *   <li><b>行数上限</b>读 {@code gate_config.max_rows} —— 它是运维口径（集群能承受多少行回传），该可配；</li>
 *   <li><b>负值容差</b>与<b>空值比例</b>写死常量 —— 它们是「数据是否离谱」的判定，跟着代码走。
 *       放进 gate_config 会让闸门被一条 UPDATE 静默关掉（把它们调成 99999 就再也告不了警），
 *       这是审计 P1#18 拍板的：配置项只留给「因环境而异」的参数，不留给「判断标准」。</li>
 * </ul>
 */
@Component
@Order(5)
public class ResultCheckGate implements Gate {

    /**
     * 负值容差（票面里的 {@code max_abs_gmv}）：|值| 超过它且为负即告警。
     * 取 0.01 而不是 0：StarRocks 的 DECIMAL 聚合在分位四舍五入后可能出现 -0.001 这种浮点尾巴，
     * 卡死在 0 会让 Q9/Q15 这类比值题天天告警，告警一多就没人看。
     */
    static final BigDecimal NEGATIVE_VALUE_TOLERANCE = new BigDecimal("0.01");

    /** 空值比例告警线（票面里的 {@code null_ratio}）：整张结果里 NULL 单元格占比超过它即告警 */
    static final double MAX_NULL_RATIO = 0.2;

    private final OntologyCache cache;

    public ResultCheckGate(OntologyCache cache) {
        this.cache = cache;
    }

    @Override
    public String name() {
        return "结果自检";
    }

    @Override
    public Phase phase() {
        return Phase.RESULT;
    }

    @Override
    public GateResult check(AgentContext ctx) {
        List<Map<String, Object>> rows = ctx.getQueryResult();
        if (rows == null || rows.isEmpty()) {
            return GateResult.okWarning("无数据：查询结果为空，请检查筛选条件");
        }

        // 单行全空也算无数据（QA-05 P2-1 延伸）：SUM/AVG 在空集上返回 1 行 NULL，
        // rows 非空会绕过上面的判断，用户同样只看到 {"gmv_total": null} 这种不可读结果。
        Map<String, Object> only = rows.size() == 1 ? rows.get(0) : null;
        if (only != null && !only.isEmpty() && only.values().stream().allMatch(v -> v == null)) {
            return GateResult.okWarning("无数据：查询结果为空（聚合返回空值），请检查筛选条件");
        }

        int maxRows = cache.gateThreshold("max_rows", 1000);
        if (rows.size() > maxRows) {
            ctx.setQueryResult(new ArrayList<>(rows.subList(0, maxRows)));
        }

        // G1：异常值自检。两条都只告警不拦截 —— 负 GMV 可能是真实的退款冲正，
        // 空值可能是某天该渠道确实没投放，闸门没有资格替业务判死，只负责让用户看见。
        List<String> warnings = new ArrayList<>();
        Map.Entry<String, BigDecimal> negative = firstNegativeValue(rows);
        if (negative != null) {
            warnings.add("结果含负值（列 " + negative.getKey() + " = " + negative.getValue().stripTrailingZeros().toPlainString()
                    + "），疑似退款冲正或口径异常，请核对");
        }
        double nullRatio = nullRatio(rows);
        if (nullRatio > MAX_NULL_RATIO) {
            warnings.add("结果空值比例 " + Math.round(nullRatio * 100) + "% 超过告警线 "
                    + Math.round(MAX_NULL_RATIO * 100) + "%，该列可能缺数据");
        }
        if (!warnings.isEmpty()) {
            return GateResult.okWarning(String.join("；", warnings));
        }
        return GateResult.ok();
    }

    /** 第一处「负得离谱」的单元格：列名 + 值；没有则 null */
    private static Map.Entry<String, BigDecimal> firstNegativeValue(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> cell : row.entrySet()) {
                BigDecimal v = toDecimal(cell.getValue());
                if (v != null && v.negate().compareTo(NEGATIVE_VALUE_TOLERANCE) > 0) {
                    return Map.entry(cell.getKey(), v);
                }
            }
        }
        return null;
    }

    /**
     * 数值归一：只认 Number（驱动给的是 BigDecimal/Double/Long 等），字符串不猜 ——
     * 把 "2026-10-19" 之类的字符串硬 parse 成数字会让这条自检变成误报机器。
     */
    private static BigDecimal toDecimal(Object raw) {
        if (raw instanceof BigDecimal d) {
            return d;
        }
        if (raw instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return null;
    }

    /** 所有单元格里 NULL 的占比（分母 = 行数 × 列数，用最大列数算，避免行宽不齐时把比例算小） */
    private static double nullRatio(List<Map<String, Object>> rows) {
        int cols = 0;
        for (Map<String, Object> row : rows) {
            cols = Math.max(cols, row.size());
        }
        if (cols == 0) {
            return 0d;
        }
        long nulls = 0;
        for (Map<String, Object> row : rows) {
            for (Object v : row.values()) {
                if (v == null) {
                    nulls++;
                }
            }
        }
        return (double) nulls / ((double) rows.size() * cols);
    }
}
