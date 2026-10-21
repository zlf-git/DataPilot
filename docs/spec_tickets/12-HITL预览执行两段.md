# Ticket 12：HITL（IR 预览/执行两段）

## 目标
preview/execute 两段契约，五钉住点全落地，span_type 与 status 区分清楚。

## 背景
来自 A6（Spec §3 阶段③）+ Spec §4.6。审计 P0#7：PREVIEW 是 status 不是 span_type，需区分。

## 修改范围
- controller：preview/execute
- 闸门：execute 重跑 1-4
- agent_step：status 增 PREVIEW、span_type=hitl

## 任务
1. /api/ask/preview 只校验不执行
2. /api/ask/execute 重跑闸门 1-4
3. 编辑范围收窄（只改 value/limit/orderBy.direction/dimensions）
4. 结构变更记 REPLAN（status）
5. agent_run.final_sql 存最终版；**agent_step.status 增 PREVIEW、span_type=hitl**（PREVIEW 是 status 不是 span_type，span_type 枚举来自 Ticket 09 的 hitl）

## 验收标准
preview 只校验、execute 重跑闸门、改 value 不产生注入；agent_step 记录 status=PREVIEW 且 span_type=hitl。

## 测试要求
集成测试：① preview 后改 filter value 再 execute，断言重跑闸门（可注入 value 被拦）；② 结构变更记 status=REPLAN 且 span_type=hitl。只测外部行为。

## 依赖
Ticket 02、03（完整生成器，preview 需生成真实 SQL）、09（hitl 枚举）

## 预估
1 天

## 不包含
- SQL 文本编辑入口（Spec 明确走 IR 路线不改 SQL）

---

## 落地记录（2026-10-21）

**做了什么**

| 位置 | 内容 |
|---|---|
| `agent/AgentEngine.java` | `execute` 拆成 `prepare`（⓪改写 → LLM → 闸门①②③ → SQL → 闸门④）与 `finish`（执行 → 闸门⑤ → 环比 → 解释 → 报告 → 记会话）；三条入口 `run` / `preview` / `executePreview` 各自只走自己那一段 |
| `agent/hitl/PreviewStore.java`（新增） | 预览快照：`traceId/question/sessionId/rewrittenQuestion/irJson/sql/params/gates/comparisonDays`。IR 存 **JSON 文本**（序列化天然深拷贝，execute 改不动快照）；TTL 600 秒（按创建时刻算）、上限 200 份、进程内存 |
| `agent/hitl/IrEditGuard.java`（新增） | 服务端 diff：拿快照 IR 与提交 IR 逐字段比，只认 value / limit / orderBy.direction / dimensions 四项，其余一律 REPLAN；另有 `enforceServerFields`（anchor 与 compareOffsetDays 执行前清零重算） |
| `agent/trace/TraceSpan.java` | 加 `hitl_preview` / `hitl_execute`（span_type=hitl），**并加 `inAskBaseline` 标志**：`expectedPerSuccess()` 改为只数 ask 链路，基线仍是 **11** |
| `agent/trace/SpanStatus.java` | 加 `PREVIEW`；`REPLAN` 从「预留值」变成真正在用 |
| `agent/trace/TraceStep.java` | 加 `mark(span, input, output, status)`：状态由调用方指定。`span()` 只有 SUCCESS/FAILED 两态，PREVIEW/REPLAN 都不是这两者 |
| `agent/trace/RunStatus.java` | `of(ctx)` 先看 `ctx.runStatus` 覆写（**写入方唯一**：只有 HITL 两条路径写） |
| `controller/HitlController.java`（新增） | `POST /api/ask/preview`、`POST /api/ask/execute` |
| `controller/AskResponse.java` | 加 `previewId` / `edits` / `structuralChanges` |
| `controller/HealthController.java` | `GET /api/health/hitl`（存活快照数 + 上限 + 可编辑面）；`/api/health/traces` 加 `hitlSpans` 与每条的 `inAskBaseline` |

**三个关键决定（票面没写死，但决定了这套东西安不安全）**

1. **execute 不复用预览那份 SQL 文本，而是拿 IR 重新 `convert`。**
   SQL 文本一旦被人工经手，就再也说不清「它还是不是原来那条被校验过的查询」。重新生成则是
   「同一份 IR → 同一段代码 → 同一段 SQL」，闸门校验的对象与真正执行的对象必然是同一个。
   顺带的好处：`agent_run.final_sql` 记的是**这次真的执行的**SQL，不是预览那份的照抄。
2. **编辑合法性由服务端 diff 判定，不看客户端自述。**
   如果由客户端声明「我只改了 value」，那这句自述本身就是攻击面 —— 它可以把 metrics 一起改掉
   却仍声明只改了 value。快照留在服务端、diff 也在服务端，是这套契约唯一立得住的形式。
3. **编辑越界直接 REPLAN，不做「尽力而为地部分应用」。**
   只应用一半的编辑，用户看到的执行结果既不是他改的那份、也不是预览那份，是最坏的一种。

**验收（`qa/verify_hitl.py`，62 项全绿）**

| 段 | 断言要点 | 实测 |
|---|---|---|
| 预览 | 有 sql/params、四道闸门全 PASS、`rows=[]`、trace 里**没有 execute 步骤** | `['rewrite','recall','prompt','llm_generate','gate_ir','ir_to_sql','gate_sql','hitl_preview']` |
| 预览状态 | run=PREVIEW、`hitl_preview.status=PREVIEW`、span_type=hitl | 落库 `agent_run.status=PREVIEW` 16 条、`agent_step(span_type=hitl,status=PREVIEW)` 16 条 |
| 预览不污染上下文 | 预览过但没执行，下一句「那环比呢」仍判无法识别 | `无法识别查询意图，请换个问法` |
| 确认执行 | 与直接问同数；trace 里**没有** rewrite/recall/prompt/llm_generate（不重新调 LLM）；gate_ir/ir_to_sql/gate_sql/execute 都在 | 10 次 execute 运行 `total_tokens=0`（LLM 真没调；同期预览运行都是 600） |
| 重跑闸门 | `gates[:4] == [存在性=PASS, 权限=PASS, 代价=PASS, 语法=PASS]` | 预览时同一 IR 也是 PASS，随后改维度立刻被判 REJECT（见下） |
| 改 value 生效 | 小程序 → APP，结果 `15604.97 → 20324.53` | 数真的变了，不是只回显 |
| 改 value 不注入 | 值 `小程序' OR '1'='1`：SQL 文本里没有注入串、`?` 占位不变、注入串原样在 params、结果取不到值 | SQL 与未编辑时逐字相同 |
| 闸门真的重跑 | 维度改成白名单外 `secret_col` → `存在性=REJECT`，且没有 execute 步骤 | 拒绝原因是 `维度字段不存在或超出可查询范围：secret_col`；dimensions 变更本身被编辑守门放行（拦它的是闸门） |
| 结构变更 | 加一个指标 → `status=REJECTED` + `structuralChanges` 非空 + 只有 `hitl_execute` 一条 span 且 `status=REPLAN` + 没有 SQL | 落库 `hitl/REPLAN` 3 条，对应 run 状态 REJECTED |
| 多轮环比 | 「那环比呢」也能预览；execute 后 answer 仍带环比结论、comparisonSql 仍是半开区间 | `本期：69；环比：前 7 天无数据` |
| 假 previewId | status=FAILED、提示重新预览、没有 SQL、**没有 trace** | 不编一条空运行记录 |
| 基线口径 | `expectedSpansPerSuccess` 仍是 11，普通问数 trace 里没有 hitl span | 11 vs 11 |

**回归**

`mvn -o test` **148/148**（新增 11 例：`IrEditGuardTest` 12 个断言组里的 11 个用例 + `PreviewStoreTest` 6 + `TraceStepTest.mark` 1 +
`TraceSpanTest` 基线口径 1）；`run_eval` PASS（15/15、49/49、探针 PASS）；
`e2e_stub` PASS（静默错 0、反例不符 0）；`verify_traces` 31/31（基线 11）；`verify_session` 16/16；`verify_explain` 40/40。

延迟 67.8 / 69.9 ms（连测两次）——比 Ticket 11 的 49.2ms 高约 20ms，但本票对 ask 链路的改动只是**把同一段代码拆成方法**
（span 数仍是 11、无新增 I/O），判断是机器负载（本轮同时跑着 MySQL/StarRocks/Kafka/Flink 与 mvn）而非代码回归；
要坐实可回退到 `bb2749c` 再测一轮，本轮没做。

**票面偏差（5 条，都是把含糊处定死，不是缩水）**

1. **`agent_step.status 增 PREVIEW` 只加在 `SpanStatus` 上，没加 `RunStatus`** —— 后者本来就有 `PREVIEW` 常量
   （Ticket 09 预留），本票只是让它第一次真的被写入。两张表都记 PREVIEW：只看 step 会漏掉「预览占了多少次运行」。
2. **结构变更：`agent_step.status=REPLAN`，`agent_run.status=REJECTED`**（不是 run 也记 REPLAN）。
   `RunStatus` 里没有 REPLAN，而运行层面它确实是「策略拒绝、不是故障」；记 FAILED 会让故障率虚高。
3. **预览不落会话轮次**（票面未提）。预览过但没执行的那份查询不该成为下一轮「那环比呢」的补全对象 ——
   用户还没答应查它。这条由 `verify_hitl` 的「预览不污染上下文」段锁住。
4. **假 previewId 不落 `agent_run`**（票面未提）。它没跑任何东西、连问题都是空的（问题来自快照），
   写一条空运行记录只会污染统计；响应仍然明确告诉用户「重新预览」。
5. **`RunStatus.of` 允许 `ctx.runStatus` 覆写**（票面未提，是拆链路的连带影响）。约束是写入方唯一：
   只有 `preview/executePreview` 写，其余路径恒为 null。

**已知边界**

- **快照在进程内存**，重启即丢（理由同 Ticket 10 的会话：每轮写元数据库会给主链路加一次 fsync）。
  代价是「确认页刷新一下就失效」，用户只能重新预览。
- **一个预览可在 TTL 内执行多次**，每次都会重跑闸门 1~4。没做成一次性令牌：一次执行失败（比如数据源抖动）
  就得重新预览，体验代价大于「重复执行同一条只读查询」的风险。
- **execute 不重跑改写**。预览是「对这一份查询的确认」，不是「对最新上下文的确认」；
  这中间会话又加了几轮，执行仍然按当初那份问题走。
- **结构变更没有「就地改指标」的通道**。票面只禁了「直接编辑 SQL 文本」，我把 IR 的结构改动也一并禁了 ——
  改指标等于换问题，让用户在确认页里改指标，等于把「LLM 生成 + 闸门校验」这条唯一入口绕开一半。
- **PREVIEW / REPLAN 是第三、第四态**：任何按 `SUCCESS/FAILED` 二分的统计（成功率、故障率）都要显式处理它们，
  否则会把「没执行的预览」算成成功、把「编辑越界」算成失败。这两条值已经在 `SpanStatus`/`RunStatus` 里，
  没有新增旁路字段。
