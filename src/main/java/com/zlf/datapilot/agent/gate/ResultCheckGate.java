package com.zlf.datapilot.agent.gate;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.knowledge.OntologyCache;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 闸门⑤ 结果自检（结果层）：对执行结果做合理性自检，把「无数据/超长结果」包装成可读反馈。
 * 不拒绝（空结果不是错误），只附加 warning；行数超阈值截断。
 * 负 GMV / 空值比例等异常值判定阈值由 Ticket 16（G1）收口，本票只做空结果 + 行数截断基础版。
 */
@Component
@Order(5)
public class ResultCheckGate implements Gate {

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

        int maxRows = cache.gateThreshold("max_rows", 1000);
        if (rows.size() > maxRows) {
            ctx.setQueryResult(new ArrayList<>(rows.subList(0, maxRows)));
        }

        return GateResult.ok();
    }
}
