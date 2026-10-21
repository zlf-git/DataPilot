package com.zlf.datapilot.agent.gate;

import com.zlf.datapilot.agent.AgentContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 闸门链：注入全部 Gate，按 phase 分组，同阶段内按 @Order 顺序执行，fail 即停。
 * AgentEngine 在三个位置分别调用 runPhase(IR/SQL/RESULT)。
 * 每个闸门的执行结果记录进 AgentContext.gateResults（链路透明回显）。
 */
@Component
public class GateChain {

    private final Map<Gate.Phase, List<Gate>> byPhase;

    public GateChain(List<Gate> gates) {
        this.byPhase = gates.stream()
                .collect(Collectors.groupingBy(
                        Gate::phase,
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    /**
     * 执行某阶段的所有闸门，任一不通过立即返回该闸门的 reject 结果。
     * 全部通过时把各闸门带的 warning 合并返回（QA-05 P2-1：此前一律 return ok()，
     * ResultCheckGate 的「无数据」提示永远到不了 AgentEngine，用户只看到空表）。
     */
    public GateResult runPhase(Gate.Phase phase, AgentContext ctx) {
        List<String> warnings = new ArrayList<>();
        for (Gate gate : byPhase.getOrDefault(phase, List.of())) {
            GateResult r = gate.check(ctx);
            ctx.addGateResult(gate.name() + "=" + (r.isPass() ? "PASS" : "REJECT")
                    + (r.isPass() && r.getWarning() != null ? "(WARN)" : ""));
            if (!r.isPass()) {
                return r;
            }
            if (r.getWarning() != null) {
                warnings.add(r.getWarning());
            }
        }
        return warnings.isEmpty() ? GateResult.ok() : GateResult.okWarning(String.join("；", warnings));
    }
}
