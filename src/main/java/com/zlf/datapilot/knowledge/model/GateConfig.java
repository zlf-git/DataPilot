package com.zlf.datapilot.knowledge.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 闸门配置实体：对应 gate_config 表。
 * 存代价/结果自检的阈值参数（管理员可调，业务不可见），如 max_join/max_metric/max_limit/max_rows。
 */
public class GateConfig {

    private Long id;

    /**
     * 闸门名（cost / existence / permission / syntax / result）。<b>不再 @NotBlank</b>（QA-13 P3-2）：
     * 服务端 {@code validateGateConfig} 立刻按 {@code param_key} 覆盖它，要求调用方填一个会被丢掉的值，
     * 只会让「只传 paramKey/paramValue」的调用方吃一个没有意义的 400。
     */
    private String gateName;

    @NotBlank(message = "paramKey 不能为空")
    private String paramKey;

    @NotBlank(message = "paramValue 不能为空")
    private String paramValue;

    private String updatedBy;

    /** 最近一次修改时间（Ticket 13 只读回显，写接口忽略该字段，由库的 ON UPDATE 维护） */
    private String updateTime;

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

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
