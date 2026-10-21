package com.zlf.datapilot.agent.gate;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.knowledge.OntologyCache;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 闸门② 权限（IR 层）：只允许读白名单内的表，且只允许读（SELECT）。
 * 双保险：① 此处校验 IR 涉及的表 ∈ 只读表白名单；② 执行固定走 StarRocks 只读账号 agent_ro（仅 SELECT 授权），
 * 即便某层漏过，写操作也会被数据库拒绝。agent_ro 是 DB 侧第二道保险，不在本闸门内校验（应用层读不到 DB 账号权限）。
 */
@Component
@Order(2)
public class AuthorityGate implements Gate {

    private final OntologyCache cache;

    public AuthorityGate(OntologyCache cache) {
        this.cache = cache;
    }

    @Override
    public String name() {
        return "权限";
    }

    @Override
    public Phase phase() {
        return Phase.IR;
    }

    @Override
    public GateResult check(AgentContext ctx) {
        QueryIr ir = ctx.getIr();
        if (ir == null) {
            return GateResult.reject("IR 为空");
        }

        // 只读表白名单（当前 = semantic_model 4 张 ADS 表，与存在性表白名单同源，语义为「可读」）
        if (ir.getMetrics() != null) {
            for (MetricRef m : ir.getMetrics()) {
                String table = cache.metricTables().get(m.getMetricId());
                if (table != null && !cache.tables().contains(table)) {
                    return GateResult.reject("越权访问表：" + table);
                }
            }
        }

        // IR 本身只含 SELECT 语义（结构化查询意图，不吐裸 SQL），写操作在账号层被 agent_ro 拦死
        return GateResult.ok();
    }
}
