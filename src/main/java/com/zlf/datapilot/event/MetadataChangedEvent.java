package com.zlf.datapilot.event;

/**
 * 元数据变更事件：指标 / 闸门参数 / <b>字段白名单</b>写入成功后发布。
 *
 * <p>三种类型都发：Ticket 02 起存在性闸门监听它刷新白名单缓存（否则改了不生效、要重启）；
 * Ticket 13 起字段的增删改也发（{@code entityType="field"}，Ticket 13 之前只有 metric/gate，
 * 注释漏改到 QA-13 P3-4 才被发现）。
 *
 * @param entityType 变更实体类型：metric / gate / field
 * @param entityKey  变更主键：metric 用 metric_id，gate 用 gate_name.param_key
 */
public record MetadataChangedEvent(String entityType, String entityKey) {
}
