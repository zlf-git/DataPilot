package com.zlf.datapilot.controller;

import com.zlf.datapilot.config.DatapilotProperties;
import com.zlf.datapilot.knowledge.model.FieldMetadata;
import com.zlf.datapilot.knowledge.model.GateConfig;
import com.zlf.datapilot.knowledge.model.MetricDef;
import com.zlf.datapilot.knowledge.model.SemanticModel;
import com.zlf.datapilot.service.MetadataService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 元数据维护接口（Ticket 01b；字段白名单写接口 Ticket 13 补）。
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

    /** 表清单（读，Ticket 13）：元数据页左侧表列表 + 字段白名单页的下拉选项 */
    @GetMapping("/metadata/tables")
    public List<SemanticModel> listTables() {
        return service.listTables();
    }

    /**
     * 字段白名单写入（写，需 write-enabled；Ticket 13）。
     * 按 (table_name, field_name) upsert —— 页面「新增字段」与「编辑字段」走同一个口，
     * 不留两个语义重叠的接口。
     */
    @PutMapping("/metadata/fields")
    public ResponseEntity<?> putField(@RequestBody FieldMetadata f) {
        checkWriteEnabled();
        return ResponseEntity.ok(service.putField(f));
    }

    /** 把字段移出白名单（写，需 write-enabled；Ticket 13）：被维度字典/指标维度引用时 400 */
    @DeleteMapping("/metadata/fields")
    public ResponseEntity<?> deleteField(@RequestParam String tableName,
                                         @RequestParam String fieldName) {
        checkWriteEnabled();
        service.deleteField(tableName, fieldName);
        return ResponseEntity.ok(Map.of("deleted", true, "tableName", tableName, "fieldName", fieldName));
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
