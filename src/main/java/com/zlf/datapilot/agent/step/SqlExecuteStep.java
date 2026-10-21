package com.zlf.datapilot.agent.step;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentStep;
import com.zlf.datapilot.sql.SqlCommand;
import com.zlf.datapilot.sql.StarRocksSqlExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ⑤ 执行节点：把 IR 转出的受控 SqlCommand 交给 StarRocks 只读账号执行。
 */
@Component
public class SqlExecuteStep implements AgentStep {

    private static final Logger log = LoggerFactory.getLogger(SqlExecuteStep.class);

    private final StarRocksSqlExecutor executor;

    public SqlExecuteStep(StarRocksSqlExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() {
        return "execute";
    }

    @Override
    public AgentContext execute(AgentContext ctx) {
        SqlCommand cmd = ctx.getSqlCommand();
        try {
            List<Map<String, Object>> rows = executor.query(cmd);
            ctx.setQueryResult(rows);
        } catch (Exception e) {
            // 不向上抛 500；也不把 DB 异常原文（BadSqlGrammarException + SQL 片段）透给用户（QA-03 P1-2），
            // 细节只进服务端日志，用 traceId 关联
            log.warn("SQL 执行失败 trace={} sql={} err={}", ctx.getTraceId(),
                    cmd == null ? null : cmd.sql(), brief(e));
            ctx.setErrorMessage("SQL 执行失败：数据源拒绝了这条查询（trace " + ctx.getTraceId()
                    + "），请把问题反馈给数据开发排查");
        }
        return ctx;
    }

    private String brief(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return t.getClass().getSimpleName();
        }
        return t.getClass().getSimpleName() + ": " + (msg.length() > 120 ? msg.substring(0, 120) + "..." : msg);
    }
}
