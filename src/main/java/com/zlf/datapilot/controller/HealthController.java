package com.zlf.datapilot.controller;

import com.zlf.datapilot.service.MetadataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查接口（Ticket 01b）。
 * GET /api/health/metadata 返回指标/字段/闸门参数条数，供运维探活与启动自检。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final MetadataService service;

    public HealthController(MetadataService service) {
        this.service = service;
    }

    @GetMapping("/metadata")
    public Map<String, Object> metadataHealth() {
        return service.metadataHealth();
    }
}
