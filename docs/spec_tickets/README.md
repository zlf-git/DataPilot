# DataPilot Agent 开发 Tickets

拆分自 `docs/开发Spec.md` **v2.1**，共 21 个 ticket，按依赖顺序排列，每票含预估工时（tickets 自身版本 v2.25）。

## 执行顺序（基础 → 核心 → 边界 → 收尾）

| 层 | Ticket | 标题 | 依赖 | 预估 |
|---|---|---|---|---|
| 基础 | 00 | 旧 3 份文档升版 | 无 | 0.2 天 |
| 基础 | 01 | 主链路骨架打通（Spring AI + AgentEngine + AskController + AskResponse 契约 + EQ filter） | 无 | 1 天 |
| 基础 | 01b | 元数据 CRUD 接口（含 metric_sql 白名单校验 + 写开关） | 无 | 0.75 天 |
| 基础 | 01c | stub IR 生成（15 条 q{n}.json，前置到 03 之前） | 01 | 0.5 天 |
| 核心 | 02 | 五道闸门 + 维度字典一对多（含 DETAIL metrics 可空） | 01、01b | 1 天 |
| 核心 | 03 | IR 结构与生成器完整实现（selectMode + 别名排序 + 日期归一化 + DETAIL 选表反查） | 01、02 | 1 天 |
| 核心 | 04 | 时间窗口（半开区间 dt > AND dt <=） | 03 | 0.5 天 |
| 核心 | 05 | 复合指标 + 销量指标 + 跨表占比 + 评测集对齐 | 04、03 | 1 天 |
| 核心 | 06a | golden_set.json 转出（只做 expect_rows 实跑校验） | 05 | 0.25 天 |
| 核心 | 06b | 评测脚本 run_eval.py | 06a | 0.5 天 |
| 核心 | 06c | 单元测试三件套 | 02、03、04 | 0.5 天 |
| 边界 | 07 | 引擎接口化（MySQL 侧不承诺只读） | 01 | 0.5 天 |
| 边界 | 08 | 多模型工厂（只产出真实 provider，不碰 stub） | 01 | 0.5 天 |
| 边界 | 09 | AgentOps 埋点 | 01 | 0.5 天 |
| 边界 | 10 | 多轮 session | 01、04 | 0.5 天 |
| 边界 | 11 | 解释 + 报告 | 01 | 0.5 天 |
| 边界 | 12 | HITL（IR 预览/执行两段） | 02、03、09 | 1 天 |
| 边界 | 13 | 前端（4 页从 0 建） | 01、01b、11 | 2.5 天 |
| 收尾 | 14 | 鉴权最小落地 | 02、01b | 0.5 天 |
| 收尾 | 15 | 代价公式补洞 | 02 | 0.25 天 |
| 收尾 | 16 | 文档与 seed 收口（G1 写死常量） | 02、03、04、05、15 | 0.5 天 |

**合计：约 14.45 人天 ≈ 14.5 天。**

> ⚠️ **单人开发没有并行**：下面的“可并行组”只在多人协作时成立。你一个人做，日历就是 14.5 天（每天 8h）。若要压缩，见下方“最短可交付路径”。

## 关键路径（单人视角，必须按序）

```
00 → 01 → 01b → 01c → 02 → 03 → 04 → 05 → 06a → 06b
```
（0.2 + 1 + 0.75 + 0.5 + 1 + 1 + 0.5 + 1 + 0.25 + 0.5 = **6.7 天到“能跑 + 能证明”**）

**最短可交付路径（约 7.7 人天，≈8 天）**：
- 必做：00、01、01b、01c、02、03、04、05、06a、06b、06c、14 —— 跑通 15 题 + 有闸门 + 有评测 + 有单测 + 有鉴权（6.7 + 0.5 单测 + 0.5 鉴权 = 7.7 天）
- 可后置（合计约 6.75 天）：07、08、09、10、11、12、13、15、16 —— 这些是“把 demo 做成产品”，不影响 15 题与简历数字

## 可并行组（仅多人协作时成立，单人开发请忽略）

- **组 A（只依赖 01）**：07、08、09、11
- **组 B（依赖 02）**：15
- **组 C（依赖 02/03）**：06c、12（12 还依赖 09）
- **组 D（依赖 01b）**：13、14

**说明**：
- 00 最先做（0.2 天，纯文档），不进关键路径。
- 核心链 01→06b 是验收目标 ≥80%（12/15）的主战场。
- 14 上线后放行 `/api/ask`、`/api/traces`，避免 01/06 集成测试 401。

## 已拍板决策（v2.3 收敛，不再是待确认）

1. **LLM**：DeepSeek，Key 用户走环境变量自填（不硬编码、不进 Git）。开发期 `provider=stub`，联调验收期 `provider=deepseek`；验收最终用真 LLM 复跑一次作为简历数据。
2. **闸门实现节奏**：一次全做（Ticket 02 五闸门一起，共用 Gate 接口 + GateChain）。
3. **B6 维度字典**：一对多（`dim_name + table_name` 多行，dt 跨 4 表）。
4. **G1 自检阈值**：写死常量（不进 gate_config）。
5. **字段层回退分支**：本期不做（Q8 靠补 sale_cnt_product 指标解决，不弱化 Ontology 白名单卖点）。

## 修订记录

### v2.25（Ticket 16 文档与 seed 收口）

- **D1 编号唯一源**：`五道闸门.md` 升 v1.2 并立「编号约定」（闸门①~⑤ 圈号、代码只认中文名），
  正文标题与 `前端.md`/`开发Spec.md`/票面 12·05/README 的「闸门 N-M」全部换成 ①~④；
  **QA-\*.md 不改**（第三方报告是历史原件，改它让 grep 变绿等于伪造评审记录）
- **C4/C5 口径**：seed 头部补 C4④「商品销售额含未支付明细」、C5②「支付用户数跨期不可加」，
  `Ontology定义.md` 新增 §6.5 两条边界（含「用户问到时怎么答」的台词）
- **G1 阈值写死常量**：`ResultCheckGate` 加负值告警（0.01 容差）与空值比例告警（20%），两者都是 `static final`，
  **不进 gate_config** —— 配置项只留给「因环境而异」的参数，不留给「判断标准」（审计 P1#18）
- **E2 验收公式**：`评测集.md` §3.1 写死六层指标分母一律 15、闸门拦截率正常态目标 0%、平均延迟含 LLM 且 stub/真模型分开报
- **交接项落地**：新增 `SchemaDrift` + `GET /api/health/drift`（元数据 ↔ 真实表/列巡检），Ticket 07 的 `listTables()` 终于有调用方
- 数据：`mvn -o test` **207/207**、`check_docs_consistency` 25/25、`eval` PASS 15/15（61.7 ms，G1 两条自检带来的开销 54.6→61.7、拦截率 0%），其余脚本无回归

### v2.24（Ticket 15 代价公式补洞）

- **公式落地为三因子兜底**：`复杂度 = max(1,指标数) × max(1,维度数) × max(1,join数)`，不再只有维度一个 `max(1,·)`。
  票面只点了维度，但单表题（join=0）与 DETAIL 明细题（指标=0）是同一个洞的另两个入口 —— 只堵一个，Q1/Q2/Q9 里至少两道照样恒通过
- **新阈值键 `max_complexity`（默认 3），比较符是 `≥` 不是 `>`**：票面验收要求「阈值=1 时能拦」，
  `>` 语义下阈值 1 = 允许复杂度 1，最简查询正是 1，等于没补。所以阈值定义为**拒绝点**，并补 seed + `V05c` 迁移 + 闸门页说明
- **实测证据**（`qa/verify_cost_gate.py`，17 项）：PUT `max_complexity=1` → Q1「10月19日当天总GMV」`REJECTED`，
  文案「复杂度 1 ≥ 阈值 1：指标 1 × 维度 0 × join 0，各因子下限 1」，且 `sql=null rows=[]`（先判后查）；恢复 3 后同一个数回来
- 数据：`mvn -o test` **196/196**、`verify_cost_gate` 17/17、`verify_hitl` 76/76、冒烟 **38/38**、`run_eval` PASS 15/15（延迟 54.6 ms、拦截率 0%）

### v2.23（QA-13 收口：2 条 P2 + 7 条 P3 全修）

- **P2-1 删除守卫补第三关**：此前只查维度字典与指标可用维度，不查口径里写的列 —— `paid_order_cnt` 被两个指标的 `metric_sql` 引用仍能摘掉，摘掉后「简单指标照跑、复合指标报字段不属于表」。现在按主表 + 分母表取指标 SQL，用前后字符边界正则匹配（不用 `LIKE`，`dt` 会命中 `create_dt`）
- **P2-2 新增 vs 覆盖分开**：`PUT /api/metadata/fields?mode=create` 撞已有键 → 409；默认仍 upsert。页面「新增」走 create，不再静默覆盖别人的 `field_role/enum_values`
- **P3 修 7 条**：删不存在 → 404（不再与参数错误混成 400）；`gateName` 去掉 `@NotBlank`（服务端派生）+ 修「gateName gateName 不能为空」重复；`PUT gate-config` 回显落库行（含 `update_time`）；事件 javadoc 补 `field`；抽屉保留维度 `alias`；抽屉 `limit` 上限对齐 `max_limit`；冒烟补 4 条正向保存 + 1 条 409 负向
- **一处报告偏严已如实说明**：QA-13 说 `paid_order_cnt` 被 3 个指标引用，实测 2 个 —— `pay_conversion_rate` 的 SQL 引用的是另一张表的同名列，按表过滤才对（否则守卫变误报机器）
- 数据：`mvn -o test` **190/190**（`MetadataServiceFieldTest` 13→17）、冒烟 **37/37**、`verify_hitl` 76/76、其余脚本无回归

### v2.22（Ticket 14 鉴权最小落地 + QA-12 P2-3 收口）

- **三角色 + HTTP Basic，只保护写接口**：`BUSINESS` 只读、`ADMIN` 全部写、`DBA` 仅字段白名单（DBA 是 admin 的子集，不是别名）。
  `PUT /api/metrics`、`PUT /api/gate-config` 仅 ADMIN；`PUT|DELETE /api/metadata/**` 归 ADMIN+DBA；其余 `/api/**` 匿名放行（票面要求 `/api/ask` 匿名 200）
- **401 与 403 分开且都是 JSON**：前者「补凭证」，后者「换账号」。401 刻意不带 `WWW-Authenticate` —— 带了会让 headless 的 fetch 挂住、真人浏览器弹原生登录框，与「无登录页」的设计冲突
- **`write-enabled` 语义变更**：从「没有鉴权时先关掉写」的安全兜底，改成「运维急停」，默认值 false → true。现在有两层：有身份 ≠ 允许写（急停态下 admin/dba 一样 403）
- **QA-12 P2-3 收口**：`previewId` 绑定生成者身份，跨身份执行 → REJECTED、不发 SQL；同一个人重试仍可执行（快照不被越权请求烧掉）
- **前端补凭证入口**（票面「不包含登录 UI」，但没它 Ticket 13 三个写页面在默认配置下全部只读）：页头按钮 + 弹窗，存 sessionStorage，401 自动弹窗
- **踩坑三条**：`@AuthenticationPrincipal(expression="username")` 在匿名 String principal 上抛异常（500 而不是 401）；`WWW-Authenticate` 让 headless fetch 卡死；`AnonymousAuthenticationToken.getName()` 是 `anonymousUser` 而非 `anonymous`
- 测试 **186/186**（新增 `SecurityAccessTest` 15 例 + `PreviewStoreTest` 3 例）；冒烟 **32/32**（默认）/ **29/29**（急停态）；`verify_hitl` **76/76**（+5 条跨身份）；`verify_traces`/`session`/`explain`/`e2e_stub` 无回归

### v2.21（QA-12 收口：3 条 P2 全修 + 5 条 P3）

- **P2-1 `limit` 下界**：改 `0`/负数原本被生成器静默放大到 `max_rows=1000`，而回显还写「limit 变更：1 → 0」。现在编辑层直接 REPLAN，生成器兜底改 `log.warn` 留痕
- **P2-2 值的形状**：`EQ` 的值改成数组/对象时编辑层不拦，SQL 发到数据源才失败 → 把自己的非法编辑误报成「数据源拒绝」。现在 `Collection`/`Map` 在编辑层 REPLAN（REJECTED vs FAILED 分得开）
- **P3 修 5 条**：TTL 文案不足 60 秒说秒（不再夸大）、LRU 淘汰注释/日志写对、`RunStatus.FAILED` javadoc 与实现对齐、结构性拒绝不再回显 `edits`（不做部分应用）、维度重复去重（`GROUP BY a, a` → `GROUP BY a`）
- **P3 披露 2 条**：`previewId` 无归属（任何持 id 者可执行并写进 id 所属会话）→ 留给 Ticket 14 的鉴权；
  同一 previewId 重复执行各占一轮会话
- 数据：`verify_hitl` **71/71**（新增 8 项）、`IrEditGuardTest` 14 例、`mvn -o test` **168/168**，其余脚本与前端冒烟零回归

### v2.20（Ticket 13 前端四页面）

- **四页面落地**：`frontend/`（React18 + Vite5 + AntD5）。问数（链路透明 + HITL 两段）、指标管理、元数据/字段白名单、闸门配置
- **问数页把「不搞黑盒」做实**：左栏结论（answer / 明细表 / 报告 iframe），右栏证据（五道闸门逐条 → SQL → 绑定参数 → IR）。参数化在页面上是**可见的**：SQL 里是 `?`，值在 params 里
- **HITL 接进问数页**：「先预览」→ 抽屉里只开放过滤值/limit/排序方向/维度 → 「执行改动」。指标与时间窗口在抽屉里**只读**（改它们等于换问题，服务端会判 REPLAN，不如一开始不给改）
- **后端补三个口**（票面「元数据页」原写「只读 + 白名单编辑」，但当时只有 `GET /fields`）：`GET /api/metadata/tables`、`PUT /api/metadata/fields`、`DELETE /api/metadata/fields`。删除前查引用（`dimension_def` / `metric_def.dimensions`），被引用即 400 并点名是谁在用
- **`GET /api/health/metadata` 增 `writeEnabled`**：页面一进来自知能不能写，不用点了保存才吃 403
- **冒烟抓出两条真 bug**：① `saveField` 的 `ON DUPLICATE KEY UPDATE` 漏了 `field_role`（改角色「返回成功但库里没变」）② `enum_values` 空串写 JSON 列报 `Invalid JSON text: empty`（写入口归一化空串→null）
- **验收 `qa/smoke_frontend.cjs`**：真实 Chrome 无头 + 本地静态托管 dist + 同源反代。**26/26**（写接口打开）/ **23/23**（关闭）。含 HITL 改值 15604.97→20324.53、白名单增删闭环（接口侧核对无残留）、站内资源零失败、控制台零意外报错
- 测试 **165/165**（新增 13 例 `MetadataServiceFieldTest`）；`verify_traces` 31/31、`verify_session` 21/21、`verify_explain` 142/142、`verify_hitl` 63/63、`e2e_stub` PASS 均无回归
- 依赖安装须走国内镜像（默认 registry 实测 6 分钟拉不完，`registry.npmmirror.com` 1 分钟装完）；`dist/`、`node_modules/` 不入库

### v2.19（QA-10 / QA-11 收口：1 条 P1 + 6 条 P2）

- **P1（QA-10，必修）单日题上环比把本期值当上期值**：上一轮是 `dt = 某一天` 时追问「那环比呢」，回 `本期：53042.41；环比：前 7 天：53042.41` —— 用户会读成「持平」。根因两处叠加：`RewriteStep` 在上一轮无相对窗口时把天数**兜底成 7 天**，而 `copyWithOffset` 只挪 `timeRange`、挪不动 `filters` 里的日期，环比 SQL 与本期**完全等价**
- **两层都拦**：①`RewriteStep` 不兜底，直接拒绝「上一轮是按天查询，环比需要「近 N 天」这类窗口」②`AgentEngine.runComparison` 见 `timeRange == null` 不执行第二次查询（防 HITL/前端绕过改写层）
- **`comparisonParams`**（QA-10 P2-1）：SQL 文本本期/环比逐字相同，只比文本「挪了」和「没挪」都过；参数才是证据。`verify_session` 判据升级为「参数恰好各差 7 天」（实测 `[10-12,10-19]` vs `[10-05,10-12]`）
- **删掉 `DEFAULT_WINDOW_DAYS` 兜底**（QA-10 P2-2）：同比文案不再把默认值当库内事实说（「库内数据只有最近 7 天」是编的前提）
- **`AgentContext.reject(reason, by)`**（QA-10 P2-3 = QA-11 P2-1）：一次写「文案 + 谁拒的」，无裸 setter；`RunStatus.of` 见 `rejectedBy` 即 `REJECTED`。三类拒绝入口：`rewrite` / `identify` / `converter`。LLM 超时仍是 FAILED
- **`chartType` 初值 `"table"` → `null`**（QA-11 P2-2）：失败响应不再谎报有图表
- **`verify_explain` 5 题 → 15 题全量**（QA-11 P2-3）：补上集成里唯一的 `line` 样本（Q14 日期首列）、每题「option 段内无裸 `<`」、环比轮次 answer 不被覆盖、拒绝时 `chartType=null`。40 项 → **142 项**
- 数据：`mvn -o test` **152/152**；`verify_session` 21/21；`verify_explain` 142/142；`verify_hitl` 63/63；`run_eval` PASS（15/15、49/49、46.8ms）；`e2e_stub` PASS；`verify_traces` 31/31
- 未处理（有意）：会话 TTL 与 500 会话上限仍只有单测 —— 长跑实测成本高于收益；`table` 形状仍无集成样本（不为凑形状编评测集外的查询）

### v2.18（Ticket 12 HITL 预览/执行两段）

- **`POST /api/ask/preview` 只校验不执行**：跑到「SQL 已生成 + 四道闸门全过」就停，`rows` 恒为空、trace 里没有 `execute` 步骤；run 状态落 `PREVIEW`（不混进成功率），`hitl_preview` 的 `status=PREVIEW`、`span_type=hitl`
- **`POST /api/ask/execute` 重跑闸门①~④**：拿快照 IR **重新 `convert` 出 SQL**（不复用预览那份文本 —— 文本一经人手就说不清还是不是那条被校验过的查询），再跑闸门①~④；**不重新调 LLM**（10 次 execute 运行 `total_tokens=0`）
- **编辑范围收窄且由服务端判定**：只认 `filters[].value` / `limit` / `orderBy[].direction` / `dimensions`，别的一律 `REPLAN`。合法性不看客户端自述，而是拿快照 IR 与提交 IR 逐字段 diff（否则「我只改了 value」这句自述本身就是攻击面）
- **结构变更 → `agent_step.status=REPLAN` + `agent_run.status=REJECTED`**，一条 SQL 都不发；不做「部分应用」（改一半的结果既不是用户改的、也不是预览那份，是最坏的一种）
- **服务端字段执行前清零**：`timeRange.anchor` 与 `compareOffsetDays` 由服务端重算，客户端指定 `compareOffsetDays` 直接 REPLAN
- **span 基线仍是 11**：`TraceSpan` 加 `inAskBaseline` 标志，`hitl_preview`/`hitl_execute` 不进普通问数基线；`SpanStatus` 加 `PREVIEW`，`REPLAN` 首次真正启用
- **`AskResponse` 加 `previewId`/`edits`/`structuralChanges`**；`GET /api/health/hitl` 暴露存活快照数、TTL、可编辑面
- 验收 `qa/verify_hitl.py` **62/62**：预览零数据行、预览不污染多轮上下文、改值生效（15604.97→20324.53）、注入串只落在 params、改白名单外维度被闸门①当场拦（证明闸门真重跑）、结构变更 REPLAN、假 previewId 明确拒绝、环比问题预览→执行仍带环比结论
- 测试 **148/148**（新增 11）；`run_eval`/`e2e_stub`/`verify_traces`(11)/`verify_session`/`verify_explain` 全绿；延迟 **51.3ms**（归档口径；开发中测到 67.8/69.9ms 是 mvn 与容器同时在跑的负载差，不是回归）
- 边界：快照在进程内存（重启即丢）；TTL 600 秒内可多次执行，每次重跑闸门；execute 不重跑改写；`PREVIEW`/`REPLAN` 是第三、第四态，二分统计要显式处理

### v2.17（Ticket 11 解释 + 报告）

- **ExplainStep**：模板生成 answer + 按结果形状定 `chartType`（空→table、单行单列→metric、多行单列→bar、日期首列两列→line、两列其它→bar、3+ 列→table）；**已有 answer 不覆盖** —— 结果自检的「无数据」、环比的「前 7 天无数据」优先
- **ReportStep**：ECharts HTML，**每行一条 series**（判据硬要求）；option 由 Jackson 序列化，`<` 转 `\u003c` 防 `</script>` 提前闭合；页面另附表格，CDN 拿不到时仍能看数
- **新增 `AskResponse.status`**（本票连带影响，票面没写）：Ticket 01~10 的判据隐含「answer 非空 = 没成功」，本票起成功时也有 answer，导致 15 题**全被判 `exec_failed`**（准确率 0/15 而形状分 49/49，数字自相矛盾）。改为显式 `status`（`SUCCESS/REJECTED/FAILED`，与 `agent_run.status` 同源），`run_eval`/`e2e_stub` 有 status 按 status、没有则回退旧口径
- **span 基线 9 → 11**：`TraceSpan` 加 `explain`/`report`；`verify_traces` 从 `/api/health/traces` 读基线，脚本零改动即跟随（09 票面已补注）
- 验收 `qa/verify_explain.py` **40/40**：5 题覆盖 metric/bar，断言 `status=SUCCESS`、`chartType` 与形状一致、reportHtml 含 echarts、**option JSON 可解析**、**series 长度 = rows 长度**、series 名与首行对齐
- line/table 两种形状在 15 题里没有用例，由单测覆盖（不为凑形状编评测集外的查询）
- 测试 **129/129**（新增 13）；`run_eval`/`e2e_stub`/`verify_traces`(11)/`verify_session` 全绿；延迟 59.3 → 65.9ms
- 边界：ECharts 走 CDN（无网时图表空白、表格仍在）；`chartType` 只有形状规则没有语义

### v2.16（Ticket 10 多轮 session）

- **ask 带 sessionId 后能续上下文**：`agent/session/SessionStore` 存「轮次摘要」（问题 + 改写结果 + 指标/维度 + 窗口天数 + SQL），不存整份 IR —— 存 IR 会诱使后续代码直接复用上一轮 SQL，那等于绕过本轮白名单与闸门。三条上限：单会话 5 轮 / TTL 30 分钟（按最后一轮写入时间）/ 会话数 500（淘汰最久未用）
- **补全只做「能确定语义」的三种情况**：环比（补成「上一轮问题 + 环比：与前 N 天对比」并跑第二窗口）、纯指代（「再算一遍」等价上一轮）、其它带新维度/新时间的追问**显式拒绝**并提示完整问法。后者是刻意的：拼接只能拼出「上一轮问题 + 一句新话」，桩/LLM 很可能只答上一轮，那是静默错数
- **环比不改 IR 契约**：`TimeRange.compareOffsetDays` 由服务端填（模型不产出），把窗口整体前移 N 天，仍是半开 `(anchor-offset-days, anchor-offset]`；环比那次执行复用本期 IR 的表/字段（已被闸门校验），只差两个日期参数
- **同比明确拒绝**：库内只有 7 天数据，没有去年同期，不编数
- 验收 `qa/verify_session.py` **16/16**：本期 69 → 追问「那环比呢」→ 补全结果、`comparisonSql` 同模板、`comparisonNote=前 7 天无数据`、answer = 「本期：69；环比：前 7 天无数据」；无 sessionId / 换 sessionId 追问都判「无法识别」（不猜、不串）
- 「前 7 天无数据」是真查出来的空：数据 `dt` 仅 `2026-10-13~10-19`，前 7 天窗口 `(10-12, 10-05]` 落在数据外；单测 `comparisonOffsetShiftsWindowKeepingShape` 锁住参数确实前移（`2026-10-05/2026-10-12`），防「文案说挪了、SQL 没挪」
- `GET /api/health/session` 暴露存活会话数与三条上限（`storage=memory`）；测试 **116/116**（新增 16）；`run_eval`/`e2e_stub`/`verify_traces` 零回归
- 边界：会话在进程内存，重启即清空（有意的降级，避免每轮 fsync 写库）；环比那次执行不再单独过语法闸门；只支持「前一个等长窗口」

### v2.15（QA-08 多模型工厂收口：08b 补丁）

QA-08 判 **PASS，0 P0 / 0 P1，7 条 P2**，本轮全部收口。核心结论一句话：**「空值 = 默认值」必须由装配侧决定**，不能让构造期单方面归一。

- **P2-1（同族 1/2）`provider` 空串/空白/整行缺失 → 容器里没有 `LlmClient`，启动失败且报错不提配置名**：`@ConditionalOnProperty(matchIfMissing=true)` 只认「属性整行缺失」，认不出「属性存在但为空串」。新增 `llm/OnLlmProvider`（`normalize`：null/空白→`stub`），桩改 `@Conditional`，真实侧条件复用同一归一
- **P2-3（同族 2/2）`engine.type` 空串 → 两个执行器都不装配**：`EngineJdbc` 归成 `starrocks`、条件注解两个都不匹配。新增 `sql/EngineTypes` + `sql/OnEngineType`，构造/条件/健康检查三个入口只剩一处规则（这是 07b 只修了一半的地方）
- **P2-2 删死代码**：`/api/health/llm` 的 `client == null` 分支不可达（无 bean 时应用起不到能响应 HTTP 的地步），改直接注入
- **P2-4 补安全边界测试**：`explicitApiKeyWinsOverEnv` 三档顺序（环境变量 → 显式配置赢 → 空白配置视同没配回落环境变量），此前变异 M2 对调两者 74 例全绿
- **P2-5 token 口径纠正**：真实客户端原来把端点回报的 usage 全丢弃（`entity()` 只取 body），改 `responseEntity` 从 `ChatResponse` 元数据取三项 token；假端点实测 111/222/333 → `cost=0.000555` 与端点一致。**桩的 500/100 有意保留**（09 的 `cost>0` 判据靠它），代价是桩下的成本表是常数、不可当成本数据引用——这条与 09 票面判据说明口径一致
- **P2-6 互斥用例搬到真实上下文**：原用例在最小上下文里断言「没有 `LlmClient`」是构造出来的真，新增 `stubIsTheOnlyClientInRealContext` 断言「有且仅有桩」
- **P2-7 只剥一层 `/v1`**：`while` → `if`，`.../v1/v1` 不再被剥到 host；补 3 条归一断言（含网关中间路径不动）
- 实测（非单测推断）：`--datapilot.llm.provider=` → 起得来且 `impl=StubLlmClient`；`--datapilot.engine.type=` → 起得来且 `type=starrocks`；两者此前都是 exit=1
- 测试 **100/100**（新增 6 例）；`run_eval` / `e2e_stub` / `verify_stub_ir` 零回归；18090/18091/18092 与假端点 18100 已停
- 新增脚本 `qa/fake_openai.py`（假 OpenAI 端点，可回报指定 usage 并回放收到的请求），P2-5 的实测证据由它产生，后续验真模型链路可复用

### v2.14（Ticket 09 AgentOps 埋点）

- **全链路埋点落地**：9 个 span（`rewrite/recall/prompt/llm_generate/gate_ir/ir_to_sql/gate_sql/execute/gate_result`）写 `agent_step`，收尾写 `agent_run`；`GET /api/traces`、`GET /api/traces/{traceId}` 可查；`span_type` 补齐 `operation/llm/sql/gate/hitl`（`hitl` 给 12 预留）
- **基线不写死**：`TraceSpan.values().length` 是唯一真源，`GET /api/health/traces` 暴露给 e2e，脚本读基线而非写死 8 —— 加节点只改枚举 + 引擎
- **埋点写元数据库**（`JdbcTemplate`），不跟 `engine.type` 漂移：跟着执行引擎走的话，切 MySQL 时 trace 会写进订单库、切 StarRocks 又要拿只读账号写（直接失败）
- **旁路三条硬约束**：写库异常吞掉只打 warn、span 内异常原样抛出（不破坏引擎原有的超时/服务异常分类）、先按列宽截断再插（`question/error 500`、`input/output 2000`）
- **run 状态三态**：`REJECTED`（闸门拦下）与 `FAILED`（跑挂了）分开，判据从 `gateResults` 反查，不给 `AgentContext` 加新状态位
- **票面偏差 3 处**：用「引擎内显式包一层」而非装饰器（引擎按具体类型注入 Step）；`step_name` 按实际执行单元命名（`validate` 拆成三阶段闸门）；`span_type` 不含 `vector`（Milvus 关闭时召回走关键词，不放永远不产出的值）
- **本票暴露、挂 02**：跨表维度的拒绝发生在转换器而非闸门①（闸门只看全局字段白名单，不校验字段归属表），连带让这类运行记成 `FAILED` 而非 `REJECTED` → 追加验收项 2 写入 02 票面
- **性能退化被抓出并修掉**：首版「每步一条 insert」把平均延迟从 28.6ms 抬到 231.5ms；换 `batchUpdate` 只到 190ms（batch 不合并提交，大头是 fsync）；最终「多值 INSERT + 与 run 同事务」回到 **55.9ms**（埋点自身 +27ms），准确率/形状/探针全不变
- 测试 **94/94**（新增 20）；`qa/verify_traces.py` **31 项全绿**；`run_eval` / `e2e_stub` / `verify_stub_ir` 全 PASS 零回归

### v2.13（QA-06c / QA-07 二审复核：06e 补丁 + 07c 记录）

评审方在 `/tmp` 副本与隔离库 `qa_dp07` 里独立复验：06c 跑 15 组变异、07 真切 MySQL。结论 **两票均 PASS，0 P0 / 0 P1 代码缺陷**（06c 那条 P1「基线没处理完」已在 06d 修掉）。

- **06e（新修 P3）**：`sources` 缺条目被当成「漂移」重复计数，摘要行显示「1 处漂移」而明细写「缺条目」。拆成 `source_missing()`（登记完整性，**不受 `--allow-stale` 宽宥**）+ `source_drift()`（内容是否变了，可降级告警），摘要行改三态。六种场景实测：原样 PASS / 干净副本 PASS / 缺条目 FAIL / 纯漂移 FAIL / `sources={}` FAIL / 缺条目 + `--allow-stale` 仍 FAIL
- **复核确认（我的披露被证实）**：U2「两组参数值恒为 `[from, anchor]`，分不清归属」—— 评审方 M08 整段互换参数后测试仍通过，说明这条边界是真实上限而非借口；`--no-replay` 比原描述更严（value 改 +0.02 仍 FAIL，逃生口只放弃连库重放）
- **07c 覆盖缺口教训**：`reportShowsBothDirectionsOfMismatch` 构造的是双向同时失配，旧单向判据本来就返回 `false` —— **用例名说双向、实际只验了「至少一个方向」**。双向必须拆两条独立用例
- **07c 未接线（挂 02 AC）**：MySQL 模式下问 ADS 问题四道闸门全 PASS、到 JDBC 才报错，根因是闸门③ 只看元数据白名单、`EngineWhitelist.effective()` 无消费方；属「语义层与引擎未绑定」，验收项写进 02 票面，不在 07 半改
- **环境坑**：Python `subprocess` 调 mvn 必须用 Windows 路径 `JAVA_HOME` + `mvn.cmd` 全路径（Git Bash 手敲能跑不代表脚本能跑）

### v2.12（QA-06c / QA-07 两份评审报告收口：06d + 07b 补丁）

- **QA-06c P1-1 真因是换行符，不是「没重跑 gen」**：`sources` 指纹原来哈希原始字节，Windows 工作区（LF）算出 `6abbf84e…`，评审机 `git archive` 出来是 CRLF 算出 `1eb75d3a…`，于是「开发机 PASS、评审机第一条命令就 FAIL」。两个脚本统一改成「文本读入 + 换行归一 `\n` + 哈希」；验收口径改成**工作区与干净副本都必须绿**。`golden_set.json` 自身 sha256 `655695b5… → ac11b499…`（06a/06b 记录里的旧值是当时事实，不回改）
- **QA-06c P2-1 防线漏洞**：`sources` 原来只查「登记了的有没有漂移」，删掉一个条目就没人查 → 加 `REQUIRED_SOURCES`，缺任一份来源直接 FAIL（变异 M5 验红）
- **QA-06c P2-3 全等断言是有意的**：`IrToSqlConverterTest` 用整条 SQL 全等，是为了「生成器输出被顺手格式化也要红」；改成片段断言等于放水。想放宽前先看 06c 票面 06d 段
- **QA-06c P2-5 修**：`chainMergesMultipleWarnings` 补第三道闸门（两道时 `join` 也能过，测不出「多个」）
- **QA-07 P2-1 修**：`/api/health/engine` 的 `consistent` 原来只判 `declaredNotInMetadata`，「切到 MySQL 但元数据还指着 4 张 StarRocks 表」这个真实场景会报 `true` → 改双向；新增「declared ⊂ metadata 单向失配」用例（变异 M3 验红）
- **QA-07 P2-2 修**：`EngineJdbc` 把 `type` 空值归一成 `starrocks`，与 `matchIfMissing=true` 对齐（此前「不写这行」会抛「不受支持」，两套默认值互相矛盾）；`defaultIsStarRocksAndItIsTheOnlyExecutor` 改成真的不传 `type`（变异 M4 验红）
- **QA-07 P2-4 修**：`SqlCommand` 紧凑构造器把 `params=null` 归一成空列表，NPE 不再留到执行器
- **QA-07 P2-3 归属写死**：`query(String)`/`readableTables()` 已在用；`isReadable()` 归 Ticket 02 存在性闸门接线；`listTables()` 归 Ticket 16 语义模型巡检（02/16 票面已各加一段交接）
- 测试 74/74（新增 `SqlCommandTest` 3 + `EngineWhitelistTest` +1）；`run_eval` PASS、`check_golden_set` 双环境 PASS

### v2.11（Ticket 08 多模型工厂）

- **切模型只改一行**：`datapilot.llm.provider` = `stub`（默认）/ `deepseek` / `qwen`。base-url、模型名、Key 环境变量随 provider 自动切换，`base-url` / `model` / `api-key` 留空取默认值，填了即覆盖（私有化部署、代理网关、换模型版本都不改代码）
- **一个实现 + 参数化端点**：DeepSeek 与 Qwen 协议层同构，抽出 `OpenAiCompatibleLlmClient` 取代原 `DeepSeekLlmClient`（已删，git 历史保留）；厂商差异全部收在 `LlmProvider` 枚举（默认端点 / 默认模型 / Key 变量名）
- **工厂不产 stub，是硬拒绝**：`LlmClientFactory.resolve()` 遇 `stub`/空值直接抛，缺 Key 也抛并指明该设哪个环境变量 —— 防「配了 deepseek 但 Key 没读到 → 静默退回桩 → 拿到看着正常的错数」（审计 P0#2）
- **装配互斥用自定义 `@Conditional`**（`RealLlmProviderCondition`）而非 `@ConditionalOnProperty`（只吃单值，每加一个厂商就要多写一个 `@Bean`）；非法 provider 启动即失败，与 `engine.type` 同款
- **`descriptor()` 有真实消费方**：新增 `GET /api/health/llm` 与启动日志「LLM 就绪：provider/base-url/model/key=已配置|缺失」，避免「环境变量没导、占位符没替换」静默走默认值；Key 只报有无不回显
- **base-url 归一化**：剥尾斜杠与结尾多余的 `/v1`（Spring AI 自己拼 `/v1/chat/completions`，写成 `/v1` 会 404 且只在真实调用时暴露）；只剥结尾，网关路径中间的 v1 不动
- **本轮踩坑并修掉**：`@EventListener` 方法写成 `reportLlm(LlmClient)` 会把参数当事件体，应用「启动成功后再崩」`argument type mismatch`；改无参 + `ObjectProvider`，并补 `startupSelfCheckRunsWithoutCrash` 手动 publish 事件钉死（M1/M2 两个变异均已红过）
- 测试 70/70（新增 LlmClientFactory 13 / LlmClientConfig 6）；stub 侧 `run_eval` PASS、`e2e_stub` PASS、`verify_stub_ir` 15/15 零回归
- 运行时自检实测：stub → `StubLlmClient` 无端点；deepseek（Key 走环境变量）→ `baseUrl=http://127.0.0.1:9`（传入 `/v1/` 已归一）、`model=deepseek-chat`；端点不可达时 `/api/ask` 返回「模型服务异常…」且 `sql=null`，不静默出数
- 遗留：真模型调用未做（无 Key），QA-05 U5「真实 LLM 能否稳定选对 composite 指标」仍未收口；token 用量归 Ticket 09

### v2.10（Ticket 07 引擎接口化）

- **执行器接口化**：`SqlExecutor` 补 `query(SqlCommand)`（主链路唯一入口）与 `readableTables()`（白名单钩子），新增抽象基类 `JdbcSqlExecutor` 承载共用执行逻辑，`StarRocksSqlExecutor` / `MysqlSqlExecutor` 仅差 `@ConditionalOnProperty` + 声明表；`SqlExecuteStep` 改为依赖接口，主链路与五道闸门零改动
- **切引擎只改三行配置**：`datapilot.engine.type` = `starrocks`（默认）/ `mysql`；`type` 写错或 `url` 为空**启动即失败**，不留「两个执行器都没装配」的隐式坑
- **白名单钩子有真实消费方**：`EngineWhitelist` 计算「引擎声明 ∩ 元数据白名单」并在启动自检 + `GET /api/health/engine` 暴露双向失配；**只自检不拦截**，拒绝判定仍归 Ticket 02
- **修复 P1（Ticket 04 遗留）**：切 MySQL 后问「近 N 天」会把 `BadSqlGrammarException` 原文（含 `SELECT MAX(dt) AS m FROM orders`）漏给用户；改为干净文案 + 原文进日志，`TimeAnchorProviderTest` 钉死
- 测试 51/51（新增 EngineSwitch 8 / EngineWhitelist 4 / TimeAnchorProvider 8）；StarRocks 侧 `run_eval` PASS、`verify_stub_ir` 15/15、`e2e_stub` PASS 零回归
- MySQL 侧端到端：`SELECT COUNT(*) AS zz_orders_cnt FROM orders LIMIT 1000` → 5 行，五闸门全 PASS；临时元数据与演示数据测完清零，基线不变

### v2.9（QA-06a 报告 P2 收口，06a-d 补丁）

- **校验器不再「自证清白」**：`check_golden_set.py` 重算 `sources` 里两份来源的 sha256 并与记录比对（不一致 exit=1，`--allow-stale` 降级），修掉「文档改了、真源没重生成」的静默漂移；`sources` 由 12 位前缀改存全量
- **`--replay` 变默认**：不带重放时值/列/行/SQL 四类漂移全放行，等于只验「文件自己跟自己对得上」；新增 `--no-replay` 作离线逃生口
- **两条结构性漏判堵住**：rowset 不得 1 行 1 列（那是 scalar 形状）；窗口题集合固定 `{2,11,12,13,14}`，删一条 `window` 即失败
- **删掉逐题 `row_order_stable`**：Q8 并列行 30 次采样是 50/50 翻转，逐题 true/false 会误导后来人按位置比；改 `target.row_order_sampling` 汇总留证（本轮采样 unstable=[8]）
- `expect_value` 与 `expect_rows` 对 rowset 改集合语义比较，不再隐含「顺序有意义」
- 真源重生成后 sha256 `655695b5…`（原 `6cfa7d9e…`），`eval/run_eval.py` 重跑对齐；`result.json` 的 `dirty_files` 改为排除自身输出文件

### v2.8（Ticket 06c 单测落地）

- 新增 `src/test`：`IrToSqlConverterTest`(11) / `GateChainTest`(11) / `TimeRangeTest`(9)，`mvn test` 31/31 绿；白名单走 `support/TestOntology` 替身，**单测不连库**，评审机无 MySQL/StarRocks 也能跑
- `pom.xml` 加 `spring-boot-starter-test`（首次需联网拉 mockito/assertj，之后 `-o` 可离线）
- 闸门⑤无 reject 用例（票面「五道闸各 1 个 reject」第 5 条与 `GateResult` 契约冲突）：改为断言「不 reject + warning 穿透 GateChain」，即 QA-05 P2-1 的回归点
- **口径纠正**：StarRocks 3.3.8 实测 `SELECT 1/0 IS NULL` → 1，除零本来就返回 NULL 而非抛异常；`NULLIF(分母,0)` 的价值是语义显式 + 跨引擎可移植，不是「防崩溃」。QA-05 U1 的隐含假设据此作废
- QA-05 U2/U3 收口口径：参数顺序只能锁「from/anchor 写反」（两组相同参数整体互换不可观测）；U3 用 `verify(anchor, times(1))` 证明锚点只取一次、分母复用主表条件

### v2.7（Ticket 06a/06b 落地 + QA-05 收口）

- **评测真源与执行器分层**：`eval/golden_set.json`（06a，机读真源，`expect_rows` 全部实跑非手抄）→ `eval/run_eval.py`（06b，对拍 + 六层指标）→ `eval/result.json`（含 golden_set sha256 + 被测 commit，成对归档）。`qa/eval_expectations.json` 继续服务 `qa/e2e_stub.py`（带 status/blocker/QA 语义），两者分工不再混用
- **判定硬规则增补**：rowset 一律集合比对（Q8 两轮采样到过并列行序翻转，按位置比会误判）；窗口题必须断言 SQL 形状且 `probe_window3` 单列回归；`sql_must` 只做软检查（写法等价不算错）；`--expect-total=15` 防「删题提分」
- **05 票面三处回填**（验收时与实际落地不符）：`pay_conversion_rate` 分母 `order_cnt` → `visit_uv`；分母子查询不加 `mall_dw.` 前缀；复合列别名 `AS <metricId>` 而非 `AS ratio`
- **QA-05 八条 P2**：6 条已在 05c 收口（空结果提示真正生效 + 单行全 null 也判无数据、ratio 归因、代价闸门含分母表、seed 用 `ON DUPLICATE KEY UPDATE` 收敛、迁移去硬编码库名、写入口径契约统一）；P2-5 归 Ticket 15、P2-8 归 Ticket 16；U1~U5 五项「测不出来」的收口路由已写进 QA-05 §8.2
- **数据口径基线**：锚点日三表 GMV 合计同为 53042.41（渠道/地域/概览自洽）；`ads_product_sales_rank_di` 是每日 TOP-3 排行（21 行），不可用来核总账

### v2.6（QA-04 报告收口，04b 补丁）

- **评测硬规则**：窗口题**必须**断言 SQL 形状（禁 `BETWEEN`、必须含 `dt > ?` 与 `dt <= ?`、参数 == `[anchor-days, anchor]`）。原因：7 天窗口下界 2026-10-12 是空分区，闭区间（8 天）与半开（7 天）逐题返回值相同，只做行级对拍判不出来
- **新增值级探针** `stub_ir/probe_window3.json`（近 3 天总 GMV）：半开 157702.85 vs 闭区间 223887.35，`e2e_stub.py` 两个方向都断言
- **契约收紧**：`timeRange.relativeWindow` 由「可空、缺省按 LAST_N_DAYS」改为**必填**，缺失显式拒绝；`timeRange.anchor` 先做格式归一（`20261019`/`2026/10/19`/带时间戳）再比对，格式非法与「与数据不一致」分开报错
- 引擎侧连接池：新增 `sql/EngineJdbc.java`（Hikari），执行器与锚点查询共用；`datapilot.engine.pool-size`（默认 5）、`anchor-ttl-seconds`（默认 60）可配
- 锚点缓存改原子读 `get(key, loader)`；交付表述更正为「每张表每 TTL 最多查一次库」
- `qa/verify_stub_ir.py` 改收集式断言（一次列出全部不合规）+ 文件名只认 `^q\d+\.json$`；反例桩 `neg_*`、探针桩 `probe_*` 一并校验但不计入 15 题

### v2.5（QA-03 报告余项收口，05b 补丁）

- **契约变化 1**：`op=IN` 的 value 只认 JSON 数组，逗号串显式拒绝（值本身可能含逗号，硬切会静默改语义）；Prompt 同步约束
- **契约变化 2**：AGG/DETAIL 一律带 `ORDER BY` —— 显式排序后追加未出现的分组维度 ASC 做 tie-breaker，DETAIL 无 orderBy 时按 SELECT 列升序兜底。目的只是「同一环境两轮结果一致」，06b 仍按集合比对
- **契约变化 3**：字段名 / metricId 大小写不敏感，统一按白名单真值（小写）出 SQL
- 代价闸门阈值按生成器真实能力收口：`max_metric=1`、`max_join=1`（原 3/2 永远不可达，等于关闸门），多指标落地后调回
- `dimension_def` 补 `channel` / `province` / `product_id` → 12 行 / 9 唯一字段；dt 单维度 DETAIL 的拒绝文案给出可执行下一步
- stub 匹配：多条部分匹配（双意向拼接）判「无法识别」，不再静默只答第一问
- `qa/e2e_stub.py` 新增「期望表版本」打印 + 反例回归段（空白 ×3 / 未命中 / 双意向 / 跨表维度）+ 行序稳定性段；新增常驻反例桩 `neg_cross_table.json`

### v2.4（Ticket 05 落地后回填）

- 05 口径裁定：`pay_conversion_rate` = `SUM(paid_order_cnt)/SUM(visit_uv)`（与 ADS 表内既有列同口径 0.1/0.1/0.075），**取代 v2.3 写的 `SUM(paid_order_cnt)/SUM(order_cnt)`**——后者与 `pay_rate`（订单→支付）口径重复，且与评测集 Q6 期望值不符
- 05 新增列 `denominator_table` / `denominator_sql`（迁移脚本 `deploy/migrate/V05_metric_def_denominator.sql`），指标 11 → 13
- IR 的 `ratio` 字段停用：占比统一走 `metric_type=composite` 指标，生成器遇到 `ratio` 显式拒绝（同一语义不留两套执行路径）
- 复合指标列别名统一取 metricId（不写死 `ratio`），Q6/Q9/Q15 同一规则
- 顺带堵 QA-03 P1-1（空白问题返回 Q1 数据）、P1-2（跨表维度穿到 DB + 异常原文外泄）、P2-6（IR_Schema §2 定义与代码不一致）

### v2.3（三次复审，P0×8 + P1×11 已落）

- 01：stub_ir 挪 `src/main/resources/stub_ir/`（main 非 test）；抽象层钉死（LlmClient 薄封装 + @ConditionalOnProperty 的 StubLlmClient，08 工厂只产出真实 provider）；定义 AskResponse 契约；StubLlmClient 返回固定 usage 保 cost>0
- 新增 01c：15 条 stub_ir 生成前置（否则 03 测试时序倒挂、14 条桩 IR 是空气）
- 03：DETAIL 选表 = dimension_def field→table 反查；同步更新 stub_ir 对应题
- 05：补跨表占比指标 gmv_channel_ratio（Q15 走指标命中）；标量子查询补 SUM + 分母参数在前；修 pay_conversion_rate 裸列名→SUM(paid_order_cnt)/SUM(order_cnt)；字段层回退「不做」写死
- 06a：只做 expect_rows 实跑校验，不再碰 stub IR
- 06b：rowset 对拍只比行数 + 按位置数值/字符串集合，不比列名（Q8 别名问题）
- 12 依赖补 03；13 依赖补 11 + 预估 2.5 天 + write-enabled 联调前置；15 验收改复杂度 0→1 且阈值=1 能拦；16 依赖补 15 + G1 写死常量
- README 压缩版改「最短可交付路径约 7.7 人天」；待确认清单改「已拍板决策」

### v2.2（二次复审，6 条 P0 已落）

- README 排期去掉“并行后 6-7 天”，改单人 ≈14 天 + 7 天压缩版（单人没有并行）
- 01b：`metric_sql` 加正则白名单（聚合式/比值式）+ 写开关默认关 + 非法 PUT 断言 400 + `MetadataChangedEvent`
- 02：白名单改 Caffeine TTL 60s + 监听事件；权限闸门改“表白名单 + 只读语句”，`agent_ro` 归为 DB 侧第二道保险
- 05：composite 跨表分母用标量子查询，`metric_def` 加 `denominator_table`（Q15 不生成 JOIN，结果等价、比结果不比 SQL）
- 06a：加 `expect_type`（scalar/stringset/rowset）；06b 按 type 分支对拍，另附逐题 `reason`
- 09：判据改“step 数 ≥ 当前装配 Step 数”，不写死 8（01 阶段只有 6 Step）
- 13 预估 1.5→2.5 天（4 页从 0 建）；00 预估 0.5→0.2 天；14 的保护范围补 `/api/metrics` 写

### v2.1（对齐 Spec v2.1）

- 04 展开式改 `dt > ? AND dt <= ?`（禁 BETWEEN，闭区间会取 8 天）
- 01 最简 converter 补 EQ filter（否则 Q1 生成 7 天合计而非 53042.41）
- 新增 01b 元数据 CRUD 接口（13/14/16 都指向它）
- 06 拆成 06a（golden_set 转出）/ 06b（run_eval）/ 06c（JUnit 三件套）
- 01 桩 LLM 改 StubLlmClient 读 stub_ir/q{n}.json（否则 06 跑不了 15 题）
- 03 加日期归一化（Prompt 注入 today + 规则）并删 FilterOp 的 BETWEEN
- 09/12 区分 span_type 与 status（PREVIEW 是 status，hitl 是 span_type）
- 14 依赖改 02+01b，放行 /api/ask
- 16 依赖改 02/03/04/05，旧 3 份升版挪到 00
