package com.zlf.datapilot.controller;

import com.zlf.datapilot.knowledge.EngineWhitelist;
import com.zlf.datapilot.service.MetadataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查接口。
 * <ul>
 *   <li>{@code GET /api/health/metadata}：指标/字段/闸门参数条数（Ticket 01b），供运维探活与启动自检。</li>
 *   <li>{@code GET /api/health/engine}：当前生效的执行引擎 + 可读表白名单自检（Ticket 07），
 *       切引擎后一眼能看出「是否真的换过来了」。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final MetadataService service;
    private final EngineWhitelist engineWhitelist;

    public HealthController(MetadataService service, EngineWhitelist engineWhitelist) {
        this.service = service;
        this.engineWhitelist = engineWhitelist;
    }

    @GetMapping("/metadata")
    public Map<String, Object> metadataHealth() {
        return service.metadataHealth();
    }

    @GetMapping("/engine")
    public Map<String, Object> engineHealth() {
        return engineWhitelist.report();
    }
}
