package com.zlf.datapilot.knowledge.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 闸门配置实体：对应 gate_config 表。
 * 存代价/结果自检的阈值参数（管理员可调，业务不可见），如 max_join/max_metric/max_limit/max_rows。
 */
public class GateConfig {

    private Long id;

    @NotBlank(message = "gateName 不能为空")
    private String gateName;

    @NotBlank(message = "paramKey 不能为空")
    private String paramKey;

    @NotBlank(message = "paramValue 不能为空")
    private String paramValue;

    private String updatedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getGateName() {
        return gateName;
    }

    public void setGateName(String gateName) {
        this.gateName = gateName;
    }

    public String getParamKey() {
        return paramKey;
    }

    public void setParamKey(String paramKey) {
        this.paramKey = paramKey;
    }

    public String getParamValue() {
        return paramValue;
    }

    public void setParamValue(String paramValue) {
        this.paramValue = paramValue;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
