package com.zlf.datapilot.controller;

import com.zlf.datapilot.knowledge.EngineWhitelist;
import com.zlf.datapilot.llm.LlmClient;
import com.zlf.datapilot.llm.LlmDescriptor;
import com.zlf.datapilot.service.MetadataService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口。
 * <ul>
 *   <li>{@code GET /api/health/metadata}：指标/字段/闸门参数条数（Ticket 01b），供运维探活与启动自检。</li>
 *   <li>{@code GET /api/health/engine}：当前生效的执行引擎 + 可读表白名单自检（Ticket 07），
 *       切引擎后一眼能看出「是否真的换过来了」。</li>
 *   <li>{@code GET /api/health/llm}：当前生效的 LLM provider / base-url / model / Key 是否读到（Ticket 08），
 *       切模型后同样一眼可确认；<b>不回显 Key 值</b>。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final MetadataService service;
    private final EngineWhitelist engineWhitelist;
    private final ObjectProvider<LlmClient> llmClient;

    public HealthController(MetadataService service, EngineWhitelist engineWhitelist,
                            ObjectProvider<LlmClient> llmClient) {
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
        Map<String, Object> out = new LinkedHashMap<>();
        LlmClient client = llmClient.getIfAvailable();
        if (client == null) {
            // 装配异常不该出现在这个接口里：宁可显式报「没有客户端」，也不假装健康
            out.put("provider", null);
            out.put("error", "容器中没有 LlmClient bean（检查 datapilot.llm.provider 取值）");
            return out;
        }
        LlmDescriptor d = client.descriptor();
        out.put("provider", d.provider());
        out.put("baseUrl", d.baseUrl());
        out.put("model", d.model());
        out.put("apiKeyConfigured", d.apiKeyConfigured());
        out.put("temperature", d.temperature());
        out.put("impl", client.getClass().getSimpleName());
        return out;
    }
}
