package com.zlf.datapilot.agent.gate;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.sql.SqlValidator;
import com.zlf.datapilot.sql.ValidationResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 闸门④ 语法（SQL 层）：IR 转成 SQL 后，用 JSqlParser 解析校验语法合法性 + 二次比对表/字段白名单。
 * 双重价值：① 语法合法性；② 即使 IR 层漏过，SQL 层的表/字段提取再次兜底，幻觉归零第二道防线。
 */
@Component
@Order(4)
public class SyntaxGate implements Gate {

    private final SqlValidator validator;

    public SyntaxGate(SqlValidator validator) {
        this.validator = validator;
    }

    @Override
    public String name() {
        return "语法";
    }

    @Override
    public Phase phase() {
        return Phase.SQL;
    }

    @Override
    public GateResult check(AgentContext ctx) {
        String sql = ctx.getSqlCommand() != null ? ctx.getSqlCommand().sql() : ctx.getGeneratedSql();
        if (sql == null || sql.isBlank()) {
            return GateResult.reject("无可校验的 SQL");
        }
        ValidationResult r = validator.validate(sql);
        if (!r.isPass()) {
            return GateResult.reject(r.getReason());
        }
        return GateResult.ok();
    }
}
