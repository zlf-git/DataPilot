# Ticket 01b：元数据 CRUD 接口

## 目标
实现 GET /api/metadata/fields、GET/PUT /api/metrics、GET/PUT /api/gate-config 三组接口。

## 背景
审计 P0#3：全系列票 grep /api/metrics、/api/gate-config 命中 0，没有任何票实现元数据 CRUD 接口，但 Ticket 13/14/16 全部指向不存在的接口。Spec §2.1 只有 5 个 ask/traces 接口，缺口必须补。
审计 P0#2（复审）：`metric_sql` 是纯字符串、会被拼进最终 SQL，无鉴权的 PUT 等于开了一个绕过五闸的注入口，本票必须自带校验与写开关。

## 修改范围
- `controller`：MetadataController + HealthController
- `service`：读/写 metric_def、field_metadata、gate_config
- `config`：`datapilot.metadata.write-enabled` 开关

## 任务
1. GET /api/metadata/fields：返回字段白名单列表
2. GET /api/metrics + PUT /api/metrics：指标定义读写。**`metric_sql` 必须先过正则白名单**，只允许两种形态：
   - 聚合式 `^(SUM|COUNT|AVG|MAX|MIN)\([a-z_]+\)$`
   - 比值式 `^(SUM|COUNT|AVG|MAX|MIN)\([a-z_]+\)/(SUM|COUNT|AVG|MAX|MIN)\([a-z_]+\)$`（对应 `SUM(paid_order_cnt)/SUM(total_order_cnt)`）

   不匹配直接 400 拒——参数化只绑 filter value、不绑指标表达式，不校验即注入。**注意比值式不能简写成 `^[a-z_]+/[a-z_]+$`，会把真实指标全部拒掉**
3. GET /api/gate-config + PUT /api/gate-config：闸门阈值读写
4. PUT metric_def / gate_config 成功后 `publishEvent(MetadataChangedEvent)`，供 Ticket 02 的存在性闸门刷新白名单缓存（否则改了不生效、要重启）
5. 启动健康检查：`metric_def` 为空则 ERROR 告警（Spec §4.5），并暴露 `GET /api/health/metadata` 返回指标/字段/闸门参数条数
6. **写接口默认关闭**：`datapilot.metadata.write-enabled=false`；本地演示置 true 时仅监听 127.0.0.1；Ticket 14 落地后再绑管理员角色

## 验收标准
三组接口可读写 data_agent 库对应表，PUT 后 GET 能读到更新值；**PUT 一条 `metric_sql = "SUM(x); DROP TABLE y"` 返回 400 且库内未变更**；PUT metric_def 后白名单在 1 次事件内刷新，不需重启。

## 测试要求
集成测试：① PUT 合法指标 → GET 断言读回；② PUT 非法 metric_sql → 断言 400 且库内值未变；③ PUT gate_config limit → GET 断言更新。只测外部行为。

## 依赖
无

## 预估
0.75 天

## 不包含
- 鉴权角色绑定（Ticket 14）——本票先用 write-enabled 开关兜住
- 前端页面（Ticket 13）
