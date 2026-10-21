# QA-01b 测试报告：元数据 CRUD 接口

| 项 | 内容 |
|---|---|
| 被测票 | Ticket 01b（元数据CRUD接口，含 metric_sql 白名单 + 写开关） |
| 被测版本 | 工作区快照 2026-10-21 10:26 编译产物（对应提交 29909c2 前后） |
| 测试时间 | 2026-10-21 10:23 — 10:39 |
| 测试角色 | 独立 QA（只读，未改动被测代码） |
| 运行方式 | `mvn spring-boot:run`，`server.port=18080`（写开关默认关）/ `18081`（`--datapilot.metadata.write-enabled=true`） |
| 依赖环境 | MySQL `mysql-ai-analysis` @3307（data_agent 库，metric=11/field=28/gate=4）、StarRocks `sr-ai-analysis` @9030 |
| 结论 | 核心 DoD 4/6 通过；发现 2 个 P0 缺陷、4 个 P1/P2 问题 |

## 一、DoD 逐条判定

| # | DoD 项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 编译通过 | 通过 | `mvn -o compile` EXIT=0 |
| 2 | GET /api/metadata/fields | 通过 | 28 条，含 fieldRole（dimension/metric） |
| 3 | GET /api/metrics | 通过 | 11 条，metricId/metricSql/metricAlias/dimensions 齐，enName 已移除 |
| 4 | GET /api/gate-config | 通过 | 4 条（max_join=2 / max_metric=3 / max_limit=1000 / max_rows=1000） |
| 5 | 写开关默认关返回 403 | 通过 | 两个写接口均 403，库内计数未变 |
| 6 | 合法写 + 读回 | 通过 | PUT test_gmv_total → 200；GET 读回 12 条含该指标 |
| 7 | 非法 metric_sql 返回 400 且库内未变 | 通过 | `SUM(x); DROP TABLE y` → 400；metric_def 无 test_bad；`y` 表不存在 |
| 8 | gate_config 写 + 读回 | 通过 | max_limit 改 5000 → 200，GET 读回 5000（已恢复 1000） |
| 9 | 健康检查接口 | 通过 | `{"metricCount":11,"fieldCount":28,"gateConfigCount":4}` |
| 10 | PUT 后白名单在 1 次事件内刷新 | 不通过（不可验证） | 全仓无 `@EventListener`/`@TransactionalEventListener`，MetadataChangedEvent 为空发布，且当前无白名单缓存可刷；属 Ticket 02 范围但 DoD 此刻不成立 |
| 11 | 启动自检 metric_def 为空告警 | 部分验证 | 因工作区同时段被并发改动致编译失败，未能实跑空库实例；代码路径核对正确（count==0 → log.error，异常 catch 不阻断） |

## 二、metric_sql 白名单对抗（22 条）

注入类 20 条全部被拦（HTTP 400），覆盖：`SUM(x); DROP TABLE y`、尾随 FROM 子句、`--` 行注释、`\n` 与 `\r\n` 换行注入、`/*x*/` 块注释、UNION 拼接、`+1` 算术拼接、SELECT 前缀、GROUP BY 拼接、全角括号、null、空串、纯空白。正则锚定 + 全串匹配，metric_sql 通道未被击穿。

合法业务口径被误拒 3 例：

| 输入 | 结果 | 说明 |
|---|---|---|
| `COUNT(*)` | 400 | 最常见计数指标无法写入；spec 正则 `[a-z_]+` 不允许 `*` |
| `sum(total_gmv)` | 400 | 函数名大小写敏感 |
| `SUM (total_gmv)` | 400 | 函数与括号间空格被拒 |

另：库内 `pay_conversion_rate` 的 metric_sql 为裸列名 `pay_conversion_rate`，不匹配任何白名单，一旦 PUT 回写必然 400，seed 数据与白名单不自洽（已由后续提交 0a9d749 修正为比值式）。

## 三、P0 缺陷

### P0-1 撞 metric_name 唯一键会静默改写其他指标（数据破坏）

复现：`PUT /api/metrics` 请求体 `{"metricId":"adv_dupname","metricName":"总GMV",...}` 返回 200，但实际库内：

- `adv_dupname` 不存在
- `id=1` 的 `gmv_total` 行被 UPDATE：`metric_alias` 由 `总成交额,总交易额,总销售额` 变为 NULL，`department`、`dimensions` 同样被清空
- 接口响应返回 `metricId=adv_dupname`，读回却是 `gmv_total`，响应体与库内不一致

根因：`MetadataRepository.saveMetric` 使用 `ON DUPLICATE KEY UPDATE`，但 UPDATE 子句不含 `metric_id = VALUES(metric_id)`；表上同时存在 `uk_metric_id` 与 `uk_metric_name`，撞 name 时改的是另一行。

### P0-2 gate_config 写接口零校验

| 请求 | 实际结果 |
|---|---|
| `paramValue="abc"` | 200，落库 |
| `paramValue="-1"`（max_join） | 200，落库 |
| `paramKey="qa_ghost_key"`（不存在的键） | 200，新增一行 |

后果：代价闸门读 max_limit 时整数解析直接抛异常；负数等于把闸门关掉；无枚举白名单可无限增行污染配置表。与本票“PUT 等于开了绕过五闸的注入口”的自述风险同源，只堵了 metric_sql 未堵 gate。

## 四、P1 / P2

| 级别 | 问题 |
|---|---|
| P1 | 缺 metricId / metricName / paramValue 时抛 DataIntegrityViolationException 未被捕获，返回 500 而非 400 |
| P1 | 所有 PUT 响应体 id 恒为 null，库内实际自增 id=13，客户端拿不到主键 |
| P2 | 403 响应体丢失自定义原因，只返回通用 Forbidden（reason 未透出） |
| P2 | PUT metric 缺 tableName 也返回 200，该字段为 IR 选表依赖，缺失会静默变 null |

## 五、数据清理

测试写入的 4 条 adv_* 指标、gate 的 abc / -1 / qa_ghost_key 全部回滚（测试前 mysqldump 恢复）。最终校验 metric=11 / field=28 / gate=4、gmv_total 别名完整、max_limit=1000，与测试前一致。临时库 qa_tmp_empty 已 DROP。

## 六、修复建议

1. saveMetric 先按 metric_id 查存在性再决定 insert/update，撞 metric_name 直接 400，不用 ON DUPLICATE KEY 兜两个唯一键
2. gate 写入增加 paramKey 枚举与 paramValue 正整数校验
3. 增加全局异常处理，把 DataIntegrityViolationException 转 400，并对必填字段做 @Valid 校验
4. 白名单正则补 `COUNT(*)` 并改为大小写不敏感
