# QA-12 HITL 预览 / 执行两段 — 独立测试报告

## 1. 元信息

| 项 | 内容 |
|---|---|
| 被测票号 | Ticket 12：HITL（IR 预览 / 执行两段） |
| 被测 commit | **`db280a6`**（含 Ticket 12 本体 `f48012e` 与其后的收口提交 `fb891a6`、基线重归档 `db280a6`） |
| 被测物形态 | `git archive db280a6 \| tar -x` 到 `%TEMP%/qa12_head` 的固定副本；`mvn -o -DskipTests package` 产出的 `datapilot-0.0.1-SNAPSHOT.jar`，`java -jar` 真实进程 |
| 工作区状态 | **未作为被测对象**。测试期间工作区有 Ticket 13 在途改动（`frontend/`、`qa/smoke_frontend.cjs`，以及 `HealthController` / `MetadataController` / `MetadataRepository` / `GateConfig` / `MetadataService` 5 个文件），未编译、未提交、未触碰 |
| 测试时间 | 2026-10-21 16:20 — 16:58（GMT+8） |
| 运行端口 | 18090（默认配置）、18091（`--datapilot.hitl.preview-ttl-seconds=1`）、18092（`--datapilot.hitl.max-previews=1`） |
| 依赖环境 | StarRocks 9030 `mall_dw`（只读）、MySQL 3307 `data_agent`（追踪表只增）、`datapilot.llm.provider=stub` |
| 结论 | **通过**：票面 5 项任务、3 条验收标准、2 条测试要求全达成；`mvn -o test` **152/152**、`verify_hitl` **63/63**、独立探针 **71/72**（1 条 FAIL 即缺陷 P2-1）。新记 **3 条 P2 + 7 条 P3**，无 P0/P1 |

数字口径注记：票面写 `verify_hitl 62/62`、`mvn 148/148`，那是 `f48012e` 时刻的计数；其后 `fb891a6`（QA-10/11 收口）给 `verify_hitl.py` 加了断言、给 `RewriteStepTest`/`RunStatusTest` 加了 4 例，因此在 `db280a6` 上是 **63 项**与 **152 例**。差值可解释，不是回归。

---

## 2. DoD 逐条判定

### 2.1 票面任务（5 项）

| # | 票面要求 | 判定 | 证据 |
|---|---|---|---|
| 1 | `/api/ask/preview` 只校验不执行 | **达成** | `rows` 恒为空（`A1`/`①`）；trace 步骤 `['rewrite','recall','prompt','llm_generate','gate_ir','ir_to_sql','gate_sql','hitl_preview']` 中**没有** `execute`；该 run 落库 `status=PREVIEW`（72 条），不是 SUCCESS |
| 2 | `/api/ask/execute` 重跑闸门 1~4 | **达成** | execute 的 trace 为 `['hitl_execute','gate_ir','ir_to_sql','gate_sql','execute','gate_result','explain','report']`：`gate_ir` 与 `gate_sql` 都在；两条**真拦**证据见 2.2 |
| 3 | 编辑范围收窄（value / limit / orderBy.direction / dimensions） | **达成** | 22 组字段级枚举（§3.2）：4 个允许面全部 `SUCCESS` 且 SQL 真的变了；13 组结构面全部 `REJECTED + structuralChanges` |
| 4 | 结构变更记 REPLAN（status） | **达成** | 改指标 → 响应 `status=REJECTED`、`structuralChanges=['指标变更：[gmv_channel] → [gmv_channel, gmv_total]']`、**一条 SQL 都没有**、trace 只有 `[('hitl_execute','hitl','REPLAN')]` 一个 span、run 状态 `REJECTED` |
| 5 | `agent_run.final_sql` 存最终版；`agent_step.status` 增 PREVIEW、`span_type=hitl` | **达成** | 落库：`hitl/PREVIEW=72`、`hitl/SUCCESS=54`、`hitl/REPLAN=17`；`status='PREVIEW'` 的 run **72 条全部有 final_sql**（0 条空），`REJECTED` 的 run **0 条有 final_sql**；execute 的 `run.final_sql` 与响应 `sql` 逐字相同（重新生成的那份） |

### 2.2 验收标准（3 条）

| 标准 | 判定 | 证据 |
|---|---|---|
| preview 只校验 | **达成** | 同 2.1-1。另：预览阶段就被拦的题（「你好」→ `REJECTED`）**不给 previewId、不补 hitl span**，trace 只到 `llm_generate` |
| execute 重跑闸门 | **达成（两条硬证据，不是“span 在就算重跑”）** | ① 编辑 `dimensions=[secret_col]` → `存在性=REJECT`、`answer` 指名 `secret_col`，trace 停在 `gate_ir`、没有 `execute`；② 编辑 `limit=1000000` → `代价=REJECT`、文案 `limit 1000000 超过上限 1000`。这两条在**预览时同一份 IR 都是 PASS**，只有“闸门在 execute 段又跑了一遍”才拦得住 |
| 改 value 不产生注入 | **达成** | 值 `小程序' OR '1'='1` → SQL 与未编辑时**逐字相同**（`WHERE dt = ? AND channel_name = ? LIMIT 1000`），注入串原样落在 `params` 第 2 位，结果 `gmv_channel: null`（没有该渠道） |

### 2.3 测试要求（2 条）

| 要求 | 判定 | 证据 |
|---|---|---|
| ① preview 后改 filter value 再 execute，断言重跑闸门（可注入 value 被拦） | **达成** | 改 value 生效（`小程序 → APP`：`15604.97 → 20324.53`，数真的变了）；注入值不改变 SQL 形状。注：注入**不是**被闸门拦的，是被参数化“吃掉”的——闸门这段的“真重跑”由上表 secret_col / limit 两条证明，分工清楚 |
| ② 结构变更记 status=REPLAN 且 span_type=hitl | **达成** | `agent_step(span_type='hitl', status='REPLAN')` 17 条；REPLAN 时只有一条 span，不往 `gate_ir` 走 |
| 只测外部行为 | **达成** | 全部判据取自 HTTP 响应体 + `/api/traces/{id}` + `/api/health/hitl` + 落库字段，未读私有状态、未反射改字段 |

### 2.4 落地记录里 5 条“票面偏差”是否属实

| 偏差声明 | 复核 |
|---|---|
| `SpanStatus` 加 PREVIEW，`RunStatus` 的 PREVIEW 是原有常量首次被写入 | 属实（`agent_step.status='PREVIEW'` 与 `agent_run.status='PREVIEW'` 两侧都能查到） |
| 结构变更：step 记 REPLAN、run 记 REJECTED | 属实（17 条 hitl/REPLAN 对应 run 全为 REJECTED） |
| 预览不落会话轮次 | 属实：`preview` 后追问「那环比呢」仍「无法识别查询意图」；而 `execute` 后同样追问能补全（§3.3 F 段） |
| 假 previewId 不落 `agent_run` | 属实：TTL 过期路径执行前后 `agent_run` 计数 `1204 → 1204`，响应无 `traceId` |
| `RunStatus.of` 允许 `ctx.runStatus` 覆写，写入方唯一 | 代码属实（仅 `preview` / `executePreview` 写）；但由此带出一条**文档矛盾**，见 P3-3 |

---

## 3. 用例明细

### 3.1 官方验收脚本（`qa/verify_hitl.py`，63/63）

| 段 | 断言要点 | 实测 |
|---|---|---|
| ① 预览 | previewId 32 位、`status=PREVIEW`、有 sql/params、`rows=[]`、四道闸门全 PASS、preview 的 SQL 与直接问**逐字相同** | 全绿 |
| ② 预览不污染上下文 | 预览过但没执行，「那环比呢」→ `无法识别查询意图，请换个问法` | 全绿 |
| ③ 确认执行 | 与直接问同数（`15604.97`）；trace 无 `rewrite/recall/prompt/llm_generate`（不重调 LLM）；`gates[:4]` 齐全 + 第 5 条结果自检；`final_sql` = 本次真正执行的 SQL | 全绿 |
| ④ 改 value | `小程序 → APP`：`15604.97 → 20324.53`，`edits=['第 2 个过滤条件值变更：小程序 → APP']` | 全绿 |
| ⑤ 注入 | SQL 无注入串、`?` 占位不变、注入串只在 params、结果取不到值、闸门照样 PASS(WARN) | 全绿 |
| ⑥ 改维度 | `secret_col` → `存在性=REJECT`，被拦时无 execute 步骤，`edits` 里如实记「维度变更」 | 全绿 |
| ⑦ 结构变更 | `REJECTED` + `structuralChanges` + 只有 `hitl_execute` 一条 span（`REPLAN`）+ 无 SQL | 全绿 |
| ⑧ 假 previewId | `FAILED`、提示重新预览、无 SQL、无 traceId | 全绿 |
| ⑨ 多轮环比 | 「那环比呢」可预览，执行后 `本期：69；环比：前 7 天无数据`，`comparisonSql` 半开区间、`comparisonParams=['2026-10-05','2026-10-12']` | 全绿 |
| ⑩ 口径 | `/api/health/hitl` 报 4 项可编辑面；`expectedSpansPerSuccess=11`；普通问数 11 个 span 且无 hitl | 全绿 |

### 3.2 编辑面逐字段枚举（我自写，`qa12_probe.py` A 段，22 组）

以 `近7天GMV最高的渠道是哪个` 为基准 IR（`AGG` + `dimensions[channel_name]` + `orderBy[gmv_channel desc]` + `limit=1` + `LAST_N_DAYS 7`）。

| 组 | 编辑内容 | 期望 | 实测 | 附加断言 |
|---|---|---|---|---|
| A1 | 不传 `ir` | 原样执行 | `SUCCESS`，`edits=[]` | SQL 与预览逐字相同；不虚报“接受了编辑” |
| A2 | `orderBy[0].direction` `desc→asc` | 允许 | `SUCCESS`，`edits=['排序方向变更：desc → asc']` | SQL 尾部变 `... ASC` |
| A3 | `limit 1→3` | 允许 | `SUCCESS` | `LIMIT 3`、返回 3 行 |
| A4 | `limit 1→0` | 边界 | `SUCCESS` | **`LIMIT 1000`、返回 3 行** → P2-1 |
| A5 | `limit 1→-5` | 边界 | `SUCCESS` | **`LIMIT 1000`** → P2-1 |
| A6 | `limit 1→1000000` | 应被代价闸门拦 | `REJECTED`，`代价=REJECT`，`limit 1000000 超过上限 1000` | 未真取百万行 |
| A7 | `dimensions → []` | 允许 | `SUCCESS`，SQL 无 `GROUP BY`，单行 | — |
| A8 | `dimensions → [dt]` | 允许（闸门①放行） | `SUCCESS`，按 dt 分组 | 见 P3-5：换维度可彻底改语义 |
| A9 | `dimensions` 重复 `[channel_name, channel_name]` | 不崩 | `SUCCESS`（`GROUP BY channel_name, channel_name`） | P3-6 |
| A10 | `metrics[0] → gmv_total` | REPLAN | `REJECTED`，`指标变更：...` | 无 SQL、单 span |
| A11 | `filters 0→1` | REPLAN | `REJECTED`，`过滤条件条数变更：0 → 1` | — |
| A12 | `orderBy.field → gmv_region` | REPLAN | `REJECTED`，`排序字段变更` | — |
| A13 | `timeRange.days 7→30` | REPLAN | `REJECTED`，`窗口天数变更：7 → 30（“近 7 天”和“近 30 天”是两个问题…）` | — |
| A14 | `timeRange → null` | REPLAN | `REJECTED`，`删除时间窗口` | — |
| A15 | `relativeWindow → TODAY` | REPLAN | `REJECTED`，`窗口类型变更` | — |
| A16 | 客户端指定 `compareOffsetDays=7` | REPLAN | `REJECTED`，`窗口平移天数由服务端控制` | — |
| A17 | `selectMode AGG→DETAIL` | REPLAN | `REJECTED`，`查询模式变更` | — |
| A18 | 空 IR `{}` | REPLAN | `REJECTED`，`查询模式变更：AGG → null` | **同时回显了 `edits`（维度变更）** → P3-5 |
| A19 | 塞 `timeRange.anchor=1999-01-01` | 服务端字段，应被清空重算 | `SUCCESS`，SQL 与 params 与未编辑时**完全一致**（`['2026-10-12','2026-10-19']`） | anchor 被 `enforceServerFields` 清掉 |
| A20 | `direction='desc; DROP TABLE ...'` | 不得进 SQL | `SUCCESS`，SQL 尾部仍是 `ASC`（非 `desc` 一律按 ASC） | 无注入面 |
| A21 | REPLAN 响应的字段完整性 | 无 sql/params/rows | `sql=None`、`rows=[]`、trace 只有 `hitl_execute` | — |

### 3.3 请求层、生命周期、并发（`qa12_probe.py` B/C/D + `qa12_probe3.py` I/J）

| 组 | 场景 | 实测 |
|---|---|---|
| B1 | `previewId=""` | `FAILED` + 提示重新预览，HTTP 200（不 5xx） |
| B2 | 10 KB 的 `previewId` | 同上，未 5xx |
| B3 | `'0'*32` | `FAILED`、`traceId=null` |
| B4 | 请求体塞 `"sql":"DROP TABLE x"` | 被忽略，执行的仍是生成器产出的 SQL（`ExecuteRequest` 无 sql 字段） |
| B5 | 同一 `previewId` 连执行两次 | 两次都 `SUCCESS`、各落一条独立 run（与票面“可重复执行”一致） |
| B6 | 用 A 会话的 previewId 执行 | 成功，且**问题取自快照**（客户端换不了问题）；但 previewId 与 sessionId 无归属关系（P2-3） |
| B7 | 同一 preview 并发 5 次 | 5×HTTP 200，无 5xx（`parseIr` 每次给新对象，`enforceServerFields` 改不到快照） |
| B8 | 提交 200 条 filter | `REJECTED`（结构性），未 500、未拖死 |
| C1-C3 | 「你好」这类无 IR 的题 | 预览即 `REJECTED`；无 previewId；无 `hitl_preview` span（不编“待确认”） |
| I0-I7 | TTL=1s 实例 | 预览后 2.2s 执行 → `FAILED`；`agent_run` 计数 `1204 → 1204`（零写入）；`/api/health/hitl` 的 `previews` 回到 0（过期即清） |
| J0-J4 | `max-previews=1` 实例 | 连续两次预览后存活数 ≤ 1；被淘汰那份执行 → `FAILED + 请重新预览`；最新那份照常 `SUCCESS` |

### 3.4 值类型与边界（`qa12_probe2.py` E/F/G/H）

| 组 | 场景 | 实测 |
|---|---|---|
| E1 | `value` 改成数组 `["小程序","APP"]`（`EQ` + List） | `FAILED`，`SQL 执行失败：数据源拒绝了这条查询` → P2-2 |
| E2 | `value` 改成对象 `{"a":1}` | 同上 → P2-2 |
| E3 | `value` 改成 `null` | `REJECTED`，`过滤值不能为空：channel_name`（闸门层拦住了，好） |
| E4 | `value` 改成 5 万字符 | `SUCCESS`，结果空（无该渠道），未 5xx |
| E5 | `value` 改成数字 `1019` | `SUCCESS`，结果空（无该渠道） |
| F1-F3 | 预览不落会话 / 执行算一轮 | 只预览 → 「那环比呢」无法识别；预览+执行 → 「那环比呢」补全成 `近7天GMV最高的渠道是哪个，环比：与前 7 天对比` |
| G1-G2 | `limit=1000000` 的拦截路径 | `代价=REJECT`、文案点名上限；trace 停在 `gate_ir`，无 `execute` |
| H1-H2 | `limit=0` 的语义 | `SUCCESS`、`LIMIT 1000`、返回 3 行 → P2-1 |

### 3.5 落库与口径（MySQL 3307 `data_agent`）

| 项 | 实测 |
|---|---|
| `agent_run` 状态分布 | `SUCCESS 1019 / FAILED 135 / PREVIEW 72 / REJECTED 57`，共 1283 |
| `agent_step` 的 hitl 记录 | `(hitl, PREVIEW) 72`、`(hitl, SUCCESS) 54`、`(hitl, REPLAN) 17`；`54+17=71` 与 execute 运行数一致 |
| 预览 run 必须留 SQL | `status='PREVIEW' AND final_sql 为空` → **0 条** |
| 被拒 run 不许留 SQL | `status='REJECTED' AND final_sql 非空` → **0 条** |
| execute 是否重调 LLM | 挂了 `hitl_execute` 的 run，`total_tokens` 分布 **`(0, 71)`**；预览 run 是 `(600, 72)` → LLM 真的没在 execute 段跑 |
| 元数据基线 | `metric_def=13`、`field_metadata=28`、`gate_config=4`（与既有基线一致，未被本票改动） |

### 3.6 回归（同一固定副本、同一进程）

| 脚本 | 结果 |
|---|---|
| `mvn -o test` | **152/152**，BUILD SUCCESS，skipped=0 |
| `qa/verify_traces.py` | **31/31**（基线 11） |
| `qa/verify_session.py` | **21/21** |
| `qa/verify_explain.py` | **142/142** |
| `qa/e2e_stub.py` | PASS（可用 15/15、静默错 0、反例不符 0、行序不稳 0、窗口探针 157702.85 一致） |
| `qa/verify_hitl.py` | **63/63** |

---

## 4. 缺陷清单

### P0 / P1

无。

### P2

| 编号 | 问题 | 复现 | 位置 |
|---|---|---|---|
| **P2-1** | **`limit` 编辑只设上限、不设下界：`0` 与负数被静默替换成 `max_rows=1000`**，而 `edits` 仍回显「limit 变更：1 → 0」。同一编辑面「1000000 被代价闸门明确拦下」与「0/-5 静默放大 1000 倍」并存，用户读到的编辑结果与执行语义不一致 | A4 / A5 / H1：`limit=0` 与 `limit=-5` 均 `SUCCESS` + `LIMIT 1000`，`limit=0` 实回 3 行 | `sql/IrToSqlConverter.java:657-666 appendLimit()`（`if (limit <= 0) limit = DEFAULT_MAX_ROWS;`）+ `agent/hitl/IrEditGuard.java:99-101`（只比是否变化，不判合法性） |
| **P2-2** | **编辑面允许改 `value` 但不过问类型**：`EQ` 的值改成数组/对象时不产生编辑级拒绝，而是把 SQL 发到数据源再失败，用户看到的是「数据源拒绝了这条查询」——把自己的非法编辑误报成数据源故障 | E1 / E2：`status=FAILED`，`answer=SQL 执行失败：数据源拒绝了这条查询` | `agent/hitl/IrEditGuard.java:242-250 sameValue()` 只做字符串等价比较，没有类型校验；`agent/gate/*` 也没有值类型这一层 |
| **P2-3** | **`previewId` 无归属**：任何请求拿到 id 就能执行，而 execute 会把这一轮写进**快照里那个 sessionId** 的会话。当前无鉴权（Ticket 14 才做），但“预览凭据 = 写入他人会话的凭据”这一点票面未披露 | B6：用 A 会话的 previewId 执行成功，轮次落进 A | `controller/HitlController.java:53-62`（只按 id 取快照，不校验调用者）+ `agent/hitl/PreviewStore.java:52-63`（快照里存 `sessionId` 供 execute 复用） |

### P3

| 编号 | 问题 | 证据 / 位置 |
|---|---|---|
| P3-1 | TTL 提示按分钟**向上取整**，配置低于 60 秒时会夸大有效期：`--datapilot.hitl.preview-ttl-seconds=1` 实际 1 秒失效，提示仍说「1 分钟有效」 | I4 实测 `预览不存在或已过期（1 分钟有效）`；`agent/hitl/PreviewStore.java:156-159 ttlMinutes()` |
| P3-2 | 淘汰语义与注释/日志不符：`LinkedHashMap(accessOrder=true)` 淘汰的是**最久未访问**（LRU），注释与 `log.debug` 都写「最久未创建」 | `agent/hitl/PreviewStore.java:70-71`、`99-105`。影响：对某快照执行过一次（`get()` 刷新访问序）后，它反而不会被淘汰 |
| P3-3 | `RunStatus.FAILED` 的 javadoc 仍把「IR 无法识别」列为 FAILED，但 `of()` 经 `rejectedBy` 把它判成 REJECTED。同文件内两处说明互相矛盾（实测「你好」→ `REJECTED`） | `agent/trace/RunStatus.java:20-24` 与 `:44-58` |
| P3-4 | 预览响应的 `gates` 是 4 条、执行响应是 5 条（多一条「结果自检」）。前端若按固定长度渲染会错位，票面未说明这个差 | `AgentEngine.preview()` 只到闸门④，`finish()` 才追加 `gate_result` |
| P3-5 | 结构性拒绝时仍会回显 `edits`（A18 空 IR：`structuralChanges` 与 `edits` 同时非空）。虽然实际“一处都没应用”，但响应读起来像“部分接受”，与“不做部分应用”的声明字面冲突 | `agent/AgentEngine.java:351` 在 REPLAN 判断**之前**就 `setEditAllowed` |
| P3-6 | 维度编辑接受**重复字段**（`[channel_name, channel_name]`），生成 `GROUP BY channel_name, channel_name` 并照常执行。无害但无意义，属“允许面没有做去重/合法性收口” | A9 实测 `SUCCESS` |
| P3-7 | 同一 `previewId` 重复执行 N 次，会在会话里写 N 轮（每次 execute 都算一轮）。票面只写了“可重复执行”，没写会占用 5 轮的会话配额 | B5 + F3（执行算一轮）联合推断，未单独构造淘汰实验 |

---

## 5. 数据清理

| 项 | 处理 |
|---|---|
| 进程 | 18090 / 18091 / 18092 三个进程已 `taskkill`；固定副本目录 `%TEMP%/qa12_head` 保留（供复核），不影响仓库 |
| 仓库 | **零改动**：未修改任何被测源码/脚本；`eval/result.json` 未被覆盖（本报告未跑 `run_eval` 归档口径）；工作区里 Ticket 13 的在途改动未触碰 |
| 元数据库 | 追踪表只增不删：`agent_run 1283`、`agent_step 11579`；元数据基线 `metric_def 13 / field_metadata 28 / gate_config 4` 与测试前一致 |
| 业务库 | `mall_dw` 全程只读，未建表、未改数据；`orders` 等 MySQL 业务表未写 |
| 临时文件 | 探针脚本与日志留在 `%TEMP%`（`qa12_probe.py` / `qa12_probe2.py` / `qa12_probe3.py` / `qa12_recheck.py` / `qa12_db*.py` / `qa12_*.log`），不在仓库内 |

---

## 6. 修复建议

1. **P2-1（必修，改动最小）**：把“什么算合法的 limit”从生成器挪到编辑守门——`IrEditGuard.diff` 里对 `after.getLimit() != null && after.getLimit() <= 0` 直接记结构性拒绝（文案：`limit 必须是正整数，改 0/负数会被当成“不限条数”，请回去重新问`）；`appendLimit` 的 `limit <= 0` 兜底保留，但改成 `log.warn` 一次，避免“静默 1000 倍放大”继续无声发生。同理，`limit` 上界既已由代价闸门拦，下界就该在编辑层拦，不要一边严一边松。
2. **P2-2**：在 `IrEditGuard` 的允许面里给 `value` 加**标量约束**：`null` 已由闸门拦，`Collection`/`Map` 在编辑层直接 `REPLAN`（文案：`过滤值必须是单个标量`）。这样“自己的编辑不合法”与“数据源故障”在状态上就分得开（前者 REJECTED，后者 FAILED）。
3. **P2-3**：Ticket 14 落地鉴权时，把 `previewId` 与调用者绑定（快照里存 `owner`，execute 时校验；或直接把快照 key 设为 `owner + previewId`）。在此之前，至少在票面“已知边界”里补一句：**任何持 id 者可执行，并把轮次写进 id 所属会话**。
4. **P3-1**：`ttlMinutes()` 改成“不足 60 秒按秒展示”（`ttlSeconds()` 已有，拼接时二选一），避免自定义短 TTL 时提示失真。
5. **P3-2**：注释/日志改成“最久未访问”，或 `accessOrder` 置 `false`（按创建顺序淘汰，与文档一致）。二者选一，别让下一个人读注释做出错误假设。
6. **P3-3 / P3-4 / P3-5**：三处都是“响应/文档的自述与实现不符”，成本很低：`RunStatus.FAILED` 的 javadoc 删掉“IR 无法识别”；票面补一句“预览 4 条 gate、执行 5 条”；REPLAN 分支把 `setEditAllowed` 挪到判断之后（或清空），别让“没应用的编辑”出现在 `edits` 里。
7. **P3-6 / P3-7**：维度去重（`dims` 比对前 `distinct`，或在允许时提示“已忽略重复维度”）；票面“已知边界”补一句“重复执行会各占一轮会话”。

---

## 7. 复核记录（`fb891a6` 对 QA-10 / QA-11 的收口）

本票验证时顺带复核了上一轮我判为 **QA-10 P1（必修）** 的静默错数是否真的堵住（该缺陷的兜底代码就在本票改动的 `runComparison` 里）：

| 复核项 | 结果 |
|---|---|
| 单日题（`dt = 某一天`）后追问「那环比呢」 | 现在 `status=REJECTED`，`answer=上一轮是按天查询（dt = 某一天），环比需要「近 N 天」这类时间窗口…`；**不再出现** `环比：前 7 天：53042.41` |
| 同源的 DETAIL 题（无 `timeRange`）后追问 | 同样 `REJECTED`，不给数 |
| `comparisonSql` / `comparisonRows` | 均为空（没真去查“前 7 天”） |
| QA-10 P2-1（环比参数不可观测） | 已修：`comparisonParams=['2026-10-05','2026-10-12']` vs 本期 `['2026-10-12','2026-10-19']`，各差 7 天 |
| QA-11 P2-1（明确拒绝被算成 FAILED） | 已修：明确拒绝 / 无法识别 / 闸门拦 → `REJECTED`；只有执行故障 → `FAILED`（E1/E2 仍是 FAILED，区分成立） |
| QA-11 P2-2（失败响应谎报 `chartType=table`） | 已修：拒绝时 `chartType=null`、`reportHtml` 为空 |
| QA-11 P2-3（`verify_explain` 只 5 题） | 已修：扩到 15 题，实测 **142/142** |
| 遗留 | `RunStatus` 的 javadoc 与实现矛盾 → 本报告 P3-3 |
