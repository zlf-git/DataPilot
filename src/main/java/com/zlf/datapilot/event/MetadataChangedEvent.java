package com.zlf.datapilot.event;

/**
 * 元数据变更事件：PUT 指标 / 闸门参数成功后发布。
 * Ticket 02 的存在性闸门监听它刷新白名单缓存（否则改了不生效、要重启）。
 *
 * @param entityType 变更实体类型：metric / gate
 * @param entityKey  变更主键：metric 用 metric_id，gate 用 gate_name.param_key
 */
public record MetadataChangedEvent(String entityType, String entityKey) {
}
