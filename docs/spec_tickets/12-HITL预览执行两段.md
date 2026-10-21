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
2. /api/ask/execute 重跑闸门①~④
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

延迟：开发中连测两次都是 67.8 / 69.9 ms（比 Ticket 11 的 49.2ms 高约 20ms），但归档时（`git_head=f48012e`、`dirty_files=0`、
mvn 已退出）测到 **51.3 ms** —— 说明那 20ms 是同时跑着 mvn/容器的机器负载，不是代码回归。本票对 ask 链路的改动
只是把同一段代码拆成方法，span 数仍是 11、无新增 I/O。

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
- **一个预览可在 TTL 内执行多次**，每次都会重跑闸门①~④。没做成一次性令牌：一次执行失败（比如数据源抖动）
  就得重新预览，体验代价大于「重复执行同一条只读查询」的风险。
- **execute 不重跑改写**。预览是「对这一份查询的确认」，不是「对最新上下文的确认」；
  这中间会话又加了几轮，执行仍然按当初那份问题走。
- **结构变更没有「就地改指标」的通道**。票面只禁了「直接编辑 SQL 文本」，我把 IR 的结构改动也一并禁了 ——
  改指标等于换问题，让用户在确认页里改指标，等于把「LLM 生成 + 闸门校验」这条唯一入口绕开一半。
- **PREVIEW / REPLAN 是第三、第四态**：任何按 `SUCCESS/FAILED` 二分的统计（成功率、故障率）都要显式处理它们，
  否则会把「没执行的预览」算成成功、把「编辑越界」算成失败。这两条值已经在 `SpanStatus`/`RunStatus` 里，
  没有新增旁路字段。

---

## 12b 补丁：QA-12 评审收口（2026-10-21）

评审结论 **PASS**（票面 5 项任务 / 3 条验收 / 2 条测试要求全达成），无 P0/P1；新记 **3 条 P2 + 7 条 P3**。
本轮处置：**3 条 P2 全修，7 条 P3 修 5 条、披露 2 条**。

### 修掉的（都带单测或脚本断言）

| 编号 | 问题 | 修法 |
|---|---|---|
| **P2-1** | `limit` 只设上界不设下界：改 `0`/`-5` 被生成器**静默替换成 `max_rows=1000`**，而 `edits` 还回显「limit 变更：1 → 0」—— 用户读到的编辑结果与执行语义相反 | `IrEditGuard.diff` 对 `after.limit <= 0` 直接 REPLAN（文案说清「0/负数等于不限条数，会被放大到上限」）；`appendLimit` 的兜底保留但 `log.warn` 留痕（兜底不能静默） |
| **P2-2** | `EQ` 的值改成数组/对象时编辑层不拦，SQL 发到数据源才失败 → 用户看到「数据源拒绝了这条查询」，**把自己的非法编辑误报成数据源故障** | `IrEditGuard` 加标量约束：`Collection`/`Map`/数组一律 REPLAN（文案「值必须是单个标量，当前是数组（2 个元素）」），状态从此分得开（编辑非法=REJECTED，执行故障=FAILED） |
| P3-1 | TTL 提示按分钟向上取整，配 1 秒 TTL 仍说「1 分钟有效」（夸大有效期） | 新增 `PreviewStore.ttlText()`：≥60 秒说分钟，不足 60 秒说秒；`preview`/过期提示都改用它 |
| P3-2 | `LinkedHashMap(accessOrder=true)` 淘汰的是**最久未访问**，注释与日志都写「最久未创建」 | 注释与 `log.debug` 改成 LRU，并写明「执行过一次的快照反而更不易被淘汰」这个真实后果 |
| P3-3 | `RunStatus.FAILED` 的 javadoc 把「IR 无法识别」列为 FAILED，与 `of()` 经 `rejectedBy` 判 REJECTED 相反 | javadoc 改成「不含 IR 无法识别」，并说明它走 `ctx.reject(reason,"identify")` |
| P3-5 | 结构性拒绝时**同时回显 `edits`**，读起来像「部分应用」 | REPLAN 分支 `setEditAllowed(List.of())`，`setEditAllowed(verdict.allowed())` 挪到判断之后 |
| P3-6 | 维度可重复（`[channel_name, channel_name]` → `GROUP BY a, a`） | `IrEditGuard.dims()` 去重后比对 + 新增 `dedupeDimensions()` 在 `convert` 前清重；放在服务端做，不指望前端 |

### 披露未改（写进已知边界）

- **P2-3 `previewId` 无归属**：任何持 id 者都能执行，且 execute 会把这一轮写进**快照里那个 sessionId** 的会话。
  当前无鉴权（Ticket 14 才做），因此「预览凭据 = 写入他人会话的凭据」这一点必须在票面写明。修法留给 Ticket 14：
  快照存 `owner`，execute 校验（或 key 用 `owner + previewId`）。
- **P3-4 预览 4 条 gate、执行 5 条**：预览只到闸门④（还没执行，谈不上结果自检）。前端（Ticket 13）
  按「有几条渲染几条」写，不按下标取。
- **P3-7 同一 previewId 重复执行各占一轮会话**：TTL 内多次执行是刻意允许的（执行失败后不必重新预览），
  代价是占会话配额。会话 5 轮上限本来就靠「重问」自然翻篇，不为它加快照级去重。

### 验收

`verify_hitl` **63 → 71 项**（新增 8 条：limit 0/-5 拒绝、limit=10 仍执行、值数组/对象拒绝、
重复维度去重后 `GROUP BY` 只出现一次、拒绝时 `edits` 为空、过期文案带单位）；
`IrEditGuardTest` **11 → 14 例**；`mvn -o test` **168/168**；
`verify_traces` 31/31、`verify_session` 21/21、`verify_explain` 142/142、`e2e_stub` PASS、前端冒烟 26/26 均无回归。

**判据教训**（又一次同源）：写「重复维度被去重」时我先数了 SQL 里 `channel_name` 的总次数，
结果 WHERE/GROUP BY/ORDER BY/别名都含它，去重成功也判红。**判据只盯那条语义本身**（`GROUP BY` 只有一句、
且不出现 `GROUP BY a, a`），别数容器里的字面量。
