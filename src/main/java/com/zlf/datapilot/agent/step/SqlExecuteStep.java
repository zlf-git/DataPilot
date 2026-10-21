package com.zlf.datapilot.agent.step;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentStep;
import com.zlf.datapilot.sql.SqlCommand;
import com.zlf.datapilot.sql.StarRocksSqlExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ⑤ 执行节点：把 IR 转出的受控 SqlCommand 交给 StarRocks 只读账号执行。
 */
@Component
public class SqlExecuteStep implements AgentStep {

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
            // SQL 执行失败不向上抛 500，放 errorMessage 由 AskController 透出
            ctx.setErrorMessage("SQL 执行失败：" + brief(e));
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
