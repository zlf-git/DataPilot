package com.zlf.datapilot.agent.trace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentOps 埋点落库与查询（Ticket 09）。表定义见 {@code resources/schema.sql} 第 10/11 张表。
 *
 * <p><b>为什么用元数据库的 JdbcTemplate 而不是 {@code SqlExecutor}</b>：埋点是运维数据，
 * 必须<b>不随 {@code datapilot.engine.type} 漂移</b>。跟着执行引擎走的话，切到 MySQL 演示时
 * trace 会写进订单库、切到 StarRocks 时又会去只读账号上写（直接失败）。
 * 语义层与 trace 都属「平台自身数据」，固定留在元数据库。
 *
 * <p>写失败一律由上层 {@link TraceStep} 吞掉并只打日志 —— 埋点挂掉不能影响问数。
 */
@Repository
public class AgentTraceRepository {

    /** {@code agent_step.step_name} 与 {@code agent_run.status} 等短列，超长由 TraceStep 先截断 */
    private final JdbcTemplate jdbc;

    public AgentTraceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一个 span 的落库快照（record：值不可变，避免调用方改半截） */
    public record StepRow(String traceId,
                          String stepName,
                          String spanType,
                          int stepOrder,
                          String status,
                          long latencyMs,
                          String input,
                          String output,
                          int tokenUsed,
                          String errorMsg,
                          LocalDateTime startTime,
                          LocalDateTime endTime) {
    }

    /** 一次运行的落库快照 */
    public record RunRow(String traceId,
                         String sessionId,
                         String question,
                         String status,
                         long latencyMs,
                         int tokens,
                         BigDecimal cost,
                         String finalSql,
                         String errorMsg,
                         LocalDateTime startTime,
                         LocalDateTime endTime) {
    }

    /**
     * 落一次运行的全部埋点：<b>span 拼成一条多值 INSERT，且与 agent_run 同事务</b>。
     *
     * <p>为什么这么写（本机元数据库在 Windows Docker 卷、autocommit=on，实测单次问数延迟，基线 28ms）：
     * <table>
     *   <tr><th>写法</th><th>往返</th><th>commit/fsync</th><th>端到端</th></tr>
     *   <tr><td>逐条 insert</td><td>10</td><td>10</td><td>~250ms</td></tr>
     *   <tr><td>{@code batchUpdate}</td><td>1</td><td>10（batch 不合并提交）</td><td>~190ms</td></tr>
     *   <tr><td>多值 INSERT + 同事务</td><td>2</td><td>1</td><td>~50ms（埋点自身约 +22ms）</td></tr>
     * </table>
     * 结论：「批量」省的是往返，省不掉提交；提交才是大头。所以合成一条语句 + 一个事务。
     *
     * <p>同事务顺带换来原子性：run 有记录就一定有它的 span，不会出现「有汇总没明细」的半条 trace。
     * 单条语句就失败的情况（列宽/约束）会一起回滚 —— 那本就该整体失败，写半截更难查。
     */
    @Transactional
    public void saveTrace(List<StepRow> steps, RunRow run) {
        insertSteps(steps);
        insertRun(run);
    }

    private void insertSteps(List<StepRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("INSERT INTO agent_step"
                + " (trace_id, step_name, span_type, step_order, status, latency_ms,"
                + " input, output, token_used, error_msg, start_time, end_time) VALUES ");
        List<Object> args = new ArrayList<>(rows.size() * 12);
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append("(?,?,?,?,?,?,?,?,?,?,?,?)");
            StepRow r = rows.get(i);
            // 逐个 add：List.of 不接受 null，而 input/output/error_msg 本来就允许为 null
            args.add(r.traceId());
            args.add(r.stepName());
            args.add(r.spanType());
            args.add(r.stepOrder());
            args.add(r.status());
            args.add(r.latencyMs());
            args.add(r.input());
            args.add(r.output());
            args.add(r.tokenUsed());
            args.add(r.errorMsg());
            args.add(Timestamp.valueOf(r.startTime()));
            args.add(Timestamp.valueOf(r.endTime()));
        }
        jdbc.update(sql.toString(), args.toArray());
    }

    private void insertRun(RunRow r) {
        jdbc.update("""
                        INSERT INTO agent_run
                            (trace_id, session_id, question, status, total_latency_ms, total_tokens,
                             total_cost, final_sql, error_msg, start_time, end_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                r.traceId(), r.sessionId(), r.question(), r.status(), r.latencyMs(), r.tokens(),
                r.cost(), r.finalSql(), r.errorMsg(),
                Timestamp.valueOf(r.startTime()), Timestamp.valueOf(r.endTime()));
    }

    /** 最近的运行列表（按 id 倒序 = 时间倒序），sessionId 非空时按会话过滤 */
    public List<Map<String, Object>> listRuns(int limit, String sessionId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + RUN_COLUMNS + " FROM agent_run");
        if (sessionId != null && !sessionId.isBlank()) {
            sql.append(" WHERE session_id = ?");
            args.add(sessionId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> mapRun(rs), args.toArray());
    }

    /** 单条运行，查不到返回 null（由 Controller 转 404） */
    public Map<String, Object> findRun(String traceId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT " + RUN_COLUMNS + " FROM agent_run WHERE trace_id = ? ORDER BY id DESC LIMIT 1",
                (rs, i) -> mapRun(rs), traceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 某次运行的全部 span，按执行顺序 */
    public List<Map<String, Object>> listSteps(String traceId) {
        return jdbc.query("SELECT " + STEP_COLUMNS + " FROM agent_step WHERE trace_id = ? ORDER BY step_order ASC, id ASC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("stepName", rs.getString("step_name"));
                    m.put("spanType", rs.getString("span_type"));
                    m.put("stepOrder", rs.getInt("step_order"));
                    m.put("status", rs.getString("status"));
                    m.put("latencyMs", rs.getLong("latency_ms"));
                    m.put("input", rs.getString("input"));
                    m.put("output", rs.getString("output"));
                    m.put("tokenUsed", rs.getInt("token_used"));
                    m.put("errorMsg", rs.getString("error_msg"));
                    m.put("startTime", str(rs.getTimestamp("start_time")));
                    m.put("endTime", str(rs.getTimestamp("end_time")));
                    return m;
                }, traceId);
    }

    private static Map<String, Object> mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", rs.getString("trace_id"));
        m.put("sessionId", rs.getString("session_id"));
        m.put("question", rs.getString("question"));
        m.put("status", rs.getString("status"));
        m.put("latencyMs", rs.getLong("total_latency_ms"));
        m.put("tokens", rs.getInt("total_tokens"));
        BigDecimal cost = rs.getBigDecimal("total_cost");
        m.put("cost", cost == null ? null : cost.doubleValue());
        m.put("sql", rs.getString("final_sql"));
        m.put("errorMsg", rs.getString("error_msg"));
        m.put("startTime", str(rs.getTimestamp("start_time")));
        m.put("endTime", str(rs.getTimestamp("end_time")));
        return m;
    }

    private static String str(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().toString();
    }

    private static final String RUN_COLUMNS = "trace_id, session_id, question, status, total_latency_ms, "
            + "total_tokens, total_cost, final_sql, error_msg, start_time, end_time";

    private static final String STEP_COLUMNS = "step_name, span_type, step_order, status, latency_ms, "
            + "input, output, token_used, error_msg, start_time, end_time";
}
