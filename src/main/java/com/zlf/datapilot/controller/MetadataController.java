package com.zlf.datapilot.controller;

import com.zlf.datapilot.config.DatapilotProperties;
import com.zlf.datapilot.knowledge.model.FieldMetadata;
import com.zlf.datapilot.knowledge.model.GateConfig;
import com.zlf.datapilot.knowledge.model.MetricDef;
import com.zlf.datapilot.service.MetadataService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 元数据维护接口（Ticket 01b）。
 * 读接口无条件开放；写接口受 datapilot.metadata.write-enabled 开关控制（默认关，Ticket 14 落地鉴权前兜底）。
 */
@RestController
@RequestMapping("/api")
public class MetadataController {

    private final MetadataService service;
    private final DatapilotProperties props;

    public MetadataController(MetadataService service, DatapilotProperties props) {
        this.service = service;
        this.props = props;
    }

    /** 字段白名单列表（读） */
    @GetMapping("/metadata/fields")
    public List<FieldMetadata> listFields() {
        return service.listFields();
    }

    /** 指标定义列表（读） */
    @GetMapping("/metrics")
    public List<MetricDef> listMetrics() {
        return service.listMetrics();
    }

    /** 指标定义写入（写，需 write-enabled；metric_sql 过正则白名单，入参 @Valid 非空） */
    @PutMapping("/metrics")
    public ResponseEntity<?> putMetric(@Valid @RequestBody MetricDef m) {
        checkWriteEnabled();
        return ResponseEntity.ok(service.putMetric(m));
    }

    /** 闸门阈值列表（读） */
    @GetMapping("/gate-config")
    public List<GateConfig> listGateConfigs() {
        return service.listGateConfigs();
    }

    /** 闸门阈值写入（写，需 write-enabled；paramKey/paramValue 过枚举+类型校验，入参 @Valid 非空） */
    @PutMapping("/gate-config")
    public ResponseEntity<?> putGateConfig(@Valid @RequestBody GateConfig g) {
        checkWriteEnabled();
        return ResponseEntity.ok(service.putGateConfig(g));
    }

    private void checkWriteEnabled() {
        if (!props.getMetadata().isWriteEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "元数据写接口已关闭（datapilot.metadata.write-enabled=false）");
        }
    }
}
