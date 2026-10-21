package com.zlf.datapilot.controller;

import com.zlf.datapilot.agent.trace.SpanType;
import com.zlf.datapilot.agent.trace.TraceSpan;
import com.zlf.datapilot.knowledge.EngineWhitelist;
import com.zlf.datapilot.llm.LlmClient;
import com.zlf.datapilot.llm.LlmDescriptor;
import com.zlf.datapilot.service.MetadataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康检查接口。
 * <ul>
 *   <li>{@code GET /api/health/metadata}：指标/字段/闸门参数条数（Ticket 01b），供运维探活与启动自检。</li>
 *   <li>{@code GET /api/health/engine}：当前生效的执行引擎 + 可读表白名单自检（Ticket 07），
 *       切引擎后一眼能看出「是否真的换过来了」。</li>
 *   <li>{@code GET /api/health/llm}：当前生效的 LLM provider / base-url / model / Key 是否读到（Ticket 08），
 *       切模型后同样一眼可确认；<b>不回显 Key 值</b>。</li>
 *   <li>{@code GET /api/health/traces}：埋点 span 目录与「一次成功运行该有多少 span」（Ticket 09），
 *       e2e 脚本读它当基线，不写死数字。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final MetadataService service;
    private final EngineWhitelist engineWhitelist;
    private final LlmClient llmClient;

    public HealthController(MetadataService service, EngineWhitelist engineWhitelist,
                            LlmClient llmClient) {
        this.service = service;
        this.engineWhitelist = engineWhitelist;
        this.llmClient = llmClient;
    }

    @GetMapping("/metadata")
    public Map<String, Object> metadataHealth() {
        return service.metadataHealth();
    }

    @GetMapping("/engine")
    public Map<String, Object> engineHealth() {
        return engineWhitelist.report();
    }

    @GetMapping("/llm")
    public Map<String, Object> llmHealth() {
        // QA-08 P2-2：原来这里有个 `client == null` 分支，但根本没有「没有 LlmClient」的可用状态 ——
        // AgentEngine 强依赖它，缺 bean 时应用起不到能响应 HTTP 的地步（stub 已用 matchIfMissing 兜底）。
        // 「宣称有、实际不生效」的分支比没有更糟，删掉。
        Map<String, Object> out = new LinkedHashMap<>();
        LlmDescriptor d = llmClient.descriptor();
        out.put("provider", d.provider());
        out.put("baseUrl", d.baseUrl());
        out.put("model", d.model());
        out.put("apiKeyConfigured", d.apiKeyConfigured());
        out.put("temperature", d.temperature());
        out.put("impl", llmClient.getClass().getSimpleName());
        return out;
    }

    /**
     * 埋点目录自检（Ticket 09）：暴露「一次成功运行应产出多少 span」，
     * 让 e2e 脚本读基线而不是写死数字 —— 加节点时这里自动跟着变，脚本不用改。
     */
    @GetMapping("/traces")
    public Map<String, Object> traceHealth() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (TraceSpan span : TraceSpan.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stepName", span.code());
            item.put("spanType", span.type().code());
            catalog.add(item);
        }
        out.put("expectedSpansPerSuccess", TraceSpan.expectedPerSuccess());
        out.put("spanCatalog", catalog);
        out.put("spanTypes", Arrays.stream(SpanType.values()).map(SpanType::code).toList());
        return out;
    }
}
