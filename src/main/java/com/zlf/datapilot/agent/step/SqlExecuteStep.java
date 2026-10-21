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
        List<Map<String, Object>> rows = executor.query(cmd);
        ctx.setQueryResult(rows);
        return ctx;
    }
}
