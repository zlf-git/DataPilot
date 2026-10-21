# Ticket 09：AgentOps 埋点

## 目标
TraceStep 每步写 agent_step，收尾写 agent_run，GET /api/traces 可查。span_type 枚举补齐。

## 背景
来自 A1（Spec §3 阶段③）+ Spec §2.1 接口清单。审计 P0#7、P1#9：span_type 补 hitl，判据收紧。**本轮审计 P0#7：桩 LLM 不真耗 token，cost 会为 0——依赖 Ticket 01 的 StubLlmClient 返回固定 usage（in 500/out 100），保证本票 cost>0 判据在桩下可验。**

## 修改范围
- `agent/trace` 包
- AgentEngine 横切埋点
- TraceController

## 任务
1. TraceStep 横切每 Step 写 agent_step，**span_type 枚举 = operation/llm/sql/gate/hitl**（补 hitl，供 Ticket 12 用）
2. ask 收尾写 agent_run（token/cost/latency）
3. TraceController：GET /api/traces + /{traceId}

## 验收标准
一次问数后 GET /api/traces 查到 trace + span 明细：**step 数 ≥ 当前装配的 Step 数**（01 阶段 6 条起，全链 10 条），含 `span_type=llm` 与 `span_type=sql` 各至少 1，`cost > 0`、`latency_ms > 0`。

## 测试要求
集成测试：打 /api/ask 后查 /api/traces，断言 agent_run 存在 + agent_step 数 ≥ 当前装配 Step 数 + 含 llm/sql 各 1 + cost>0。**判据按实际链路动态取值、不写死 8**（01 交付时链路只有 6 Step，写死 8 会让本票当场失败）。只测外部行为。

## 依赖
Ticket 01

## 预估
0.5 天

## 不包含
- Langfuse 等重平台（Spec §6 真不做）

---

## 落地记录（2026-10-21）

### 交付物

| 文件 | 职责 |
|---|---|
| `agent/trace/SpanType.java` | `operation/llm/sql/gate/hitl` 五类（值即落库字符串） |
| `agent/trace/TraceSpan.java` | 全链路 9 个 span 目录，**「一次成功运行该有多少 span」的唯一真源**（`expectedPerSuccess()`） |
| `agent/trace/RunStatus.java` | `SUCCESS/FAILED/REJECTED/PREVIEW` + 判定逻辑 `of(ctx)` |
| `agent/trace/SpanStatus.java` | `SUCCESS/FAILED/SKIPPED/REPLAN`（span 级，与 run 级分开） |
| `agent/trace/AgentTraceRepository.java` | 写 `agent_run`/`agent_step` + 三个查询（列表/单条/明细） |
| `agent/trace/TraceStep.java` | 单次运行的 span 计时器 + 收尾写 run |
| `agent/trace/TraceRecorder.java` | `open(traceId)`，一次 ask 一个实例（有状态，不能做单例） |
| `controller/TraceController.java` | `GET /api/traces`、`GET /api/traces/{traceId}` |
| `controller/HealthController` | 新增 `GET /api/health/traces`：暴露 span 目录 + 基线 |
| `agent/AgentEngine` | 横切埋点（`try/finally` 保证 run 一定落库） |
| `qa/verify_traces.py` | e2e 验收脚本（31 项断言） |

### 关键决定

1. **埋点写元数据库，不跟执行引擎漂移**。用 `JdbcTemplate`（`spring.datasource` = 3307 元数据库）而不是 `SqlExecutor`。跟着引擎走的话，切 `engine.type=mysql` 时 trace 会写进订单库、切 StarRocks 时又要拿只读账号写（直接失败）。语义层与 trace 都属「平台自身数据」，固定留元数据库。

2. **基线不写死**。票面要求「step 数 ≥ 当前装配的 Step 数，不写死 8」。实现成 `TraceSpan.values().length`，e2e 从 `/api/health/traces` 读 `expectedSpansPerSuccess` 再与实际条数比。加节点（12 的 HITL、11 的解释）只改枚举 + 引擎，脚本与测试自动跟随。

3. **埋点是旁路，三条硬约束**：① 写库异常一律吞掉只打 warn（AgentOps 挂掉不能影响问数）；② span 里的异常原样抛出，让 `AgentEngine` 原有的「超时 / 服务异常」分类不变；③ 先按列宽截断（`question/error_msg` 500、`input/output` 2000），否则超长问题直接撞 `Data too long` 把整条埋点丢掉。

4. **run 状态三态区分**：`REJECTED`（闸门拦下，策略生效）与 `FAILED`（跑挂了，系统故障）分开，否则「拦截率」这个该盯的指标看不出来。判据从 `gateResults` 反查 `=REJECT`，不给 `AgentContext` 加新状态位 —— 已有信息能判就不新增「谁都能写」的字段。

5. **只写三阶段闸门 span，不写逐个闸门 span**（`gate_ir`/`gate_sql`/`gate_result`）。逐个写要改 `GateChain`（注入 recorder 或加 listener），把纯函数式的闸门链变成有副作用的；而每道闸门的 PASS/REJECT 已经进了 `AgentContext.gateResults`，span 的 `output` 里也带上，信息不丢。

6. **失败运行不补 SKIPPED 行**。跑到哪写到哪，`agent_run.status` 交代结局。补一堆 SKIPPED 会把 trace 里真正发生的事淹掉。

### 票面偏差（如实记录）

| 票面写的 | 实际做的 | 原因 |
|---|---|---|
| 「TraceStep 横切每 Step 写 agent_step」（暗示装饰器） | 引擎内 `trace.span(...)` 显式包一层 | `AgentEngine` 按**具体类型**注入 Step bean（`RewriteStep`/`RecallStep`/`PromptBuildStep`），装饰器要逼着全改成接口注入 + `@Primary`。横切点仍只有引擎一处，收益不抵复杂度 |
| `step_name` 例：`rewrite/recall/generate/validate/execute/explain/hitl` | `rewrite/recall/prompt/llm_generate/gate_ir/ir_to_sql/gate_sql/execute/gate_result` | 按实际执行单元命名，`validate` 拆成三阶段闸门（一阶段一条 trace 更好定位）、`explain` 归 Ticket 11 |
| 背景段提 `span_type` 含 `vector` | 只有 `operation/llm/sql/gate/hitl` | `milvus.enabled=false` 时召回走关键词，没有向量调用；等真开 Milvus 再加，避免枚举里放一个永远不产出的值 |

### 验收证据

| 项 | 结果 |
|---|---|
| `mvn -o test` | **93/93 全绿，skipped=0**（新增 `TraceStepTest` 9 / `RunStatusTest` 5 / `TraceSpanTest` 5） |
| `qa/verify_traces.py` | **PASS 31/31**：单日题 + 窗口题各 9 span = 基线 9；`llm`/`sql`/`gate` 各≥1；`cost=0.000700`；`stepOrder=[1..9]`；窗口题 `final_sql` 含 `dt > ?` 与 `dt <= ?`；两次运行 traceId 不同、sessionId 能过滤到 2 条；不存在的 traceId → 404 |
| 直查 MySQL（`data_agent`） | `agent_run`：SUCCESS 3 / FAILED 4，均价 0.0007 / 0.000525 元；`agent_step`：operation 23 / llm 6（token 3600）/ gate 11 / sql 3 |
| 异常路径 | 空白问题 → `FAILED` + 0 span；未命中意图 → `FAILED` + 4 span（停在 `llm_generate`）；反例跨表维度 → `FAILED` + 6 span（停在 `ir_to_sql`，`errorMsg` 记全了原因） |
| 回归 | `run_eval` PASS（15/15、形状 49/49、窗口探针 PASS）；`verify_stub_ir` 15/15+2；`e2e_stub` PASS（反例 6/6、行序 3/3、探针 157702.85） |

### 本票暴露、不属本票范围

反例题「按渠道名称看总GMV」的拒绝发生在 **`ir_to_sql`（转换器）而不是闸门**：`ExistenceGate` 的维度字段校验只查**全局字段白名单**，不校验「该字段属不属于这张表」，所以 `channel_name`（属于 `ads_channel_conversion_di`）顺利过闸，由 `IrToSqlConverter` 兜住并按表隔离报错。

用户看到的文案是对的、也没出数，但两件事值得记：① 「拦截率」若按 `REJECTED` 统计会漏掉这一类，实际拦截 = 闸门 REJECT + 转换期拒绝；② 这与 07c 发现的「闸门③ 只看元数据白名单、不看引擎可读表」同源 —— 白名单是全局一份，表级归属全靠转换器兜。已作为追加验收项写进 `02-五道闸门与维度字典一对多.md`（第二条）。

**摊开说**：埋点反而让这件事变得可见了 —— trace 一眼能看出「是闸门放的、转换器拦的」，这正是 per-span 埋点的价值。
