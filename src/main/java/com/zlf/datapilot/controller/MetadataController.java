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
 * 元数据维护接口（Ticket 01b；字段白名单写接口 Ticket 13 补；鉴权 Ticket 14）。
 *
 * <p>三层边界，各管各的：
 * <ol>
 *   <li><b>身份/角色</b>（{@code SecurityConfig}）：谁能写。指标与闸门仅管理员，白名单需管理员或 DBA。</li>
 *   <li><b>急停开关</b>（{@code datapilot.metadata.write-enabled}）：此刻能不能写。
 *       Ticket 14 之前它默认 {@code false}，作用是「没有鉴权时先把写接口关掉」；
 *       鉴权落地后它的语义变了 —— 从「安全兜底」变成「运维急停」（演示前一键冻结写入），
 *       默认值也随之改成 {@code true}。两层都要过：有身份 ≠ 允许写。</li>
 *   <li><b>入参校验</b>（{@code @Valid} + service 层白名单）：写什么。
 *       {@code metric_sql} 过正则白名单，字段名过格式校验，删白名单前查引用。</li>
 * </ol>
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
     * 表注册（写，需 write-enabled；Ticket 19）。
     *
     * <p>补这个接口的原因是「加表零改动」曾经不成立：元数据只有读接口，加一张表只能手工
     * {@code INSERT INTO semantic_model}，绕过急停开关与审计。语义与字段一致：
     * {@code mode=create} 已注册则 409，默认 {@code upsert} 覆盖注释/业务域。
     */
    @PutMapping("/metadata/tables")
    public ResponseEntity<?> putTable(@RequestBody SemanticModel m,
                                      @RequestParam(defaultValue = "upsert") String mode) {
        checkWriteEnabled();
        return ResponseEntity.ok(service.putTable(m, "create".equalsIgnoreCase(mode)));
    }

    /**
     * 注销一张表（写，需 write-enabled；Ticket 19）。
     *
     * <p>被字段白名单、指标主表/分母表、维度字典、metric_table 任一处引用时 400 并列出引用方，
     * 不做级联删除。
     */
    @DeleteMapping("/metadata/tables")
    public ResponseEntity<?> deleteTable(@RequestParam String tableName) {
        checkWriteEnabled();
        service.deleteTable(tableName);
        return ResponseEntity.ok(Map.of("deleted", true, "tableName", tableName));
    }

    /**
     * 字段白名单写入（写，需 write-enabled；Ticket 13）。
     *
     * <p>按 (table_name, field_name) upsert —— 页面「编辑字段」用它。但如果「新增字段」也用它，
     * 「新增」实际就是覆盖：填一个已存在的字段名不报重复，而是把原来的 field_role / field_comment /
     * enum_values 静默盖掉（QA-13 P2-2）。所以加了 {@code mode}：
     * <ul>
     *   <li>{@code mode=create}（页面「新增」）→ 已存在则 <b>409</b>，不覆盖；</li>
     *   <li>{@code mode=upsert}（默认，页面「编辑」/ 第三方同步）→ 覆盖。</li>
     * </ul>
     * 默认仍是 upsert 而不是 create：这是已有的对外契约，改默认值等于让老调用方突然开始吃 409。
     */
    @PutMapping("/metadata/fields")
    public ResponseEntity<?> putField(@RequestBody FieldMetadata f,
                                      @RequestParam(defaultValue = "upsert") String mode) {
        checkWriteEnabled();
        return ResponseEntity.ok(service.putField(f, "create".equalsIgnoreCase(mode)));
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
                    "写入已被运维急停（datapilot.metadata.write-enabled=false），重启并置 true 后再改");
        }
    }
}
