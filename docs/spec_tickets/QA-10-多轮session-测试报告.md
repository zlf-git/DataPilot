# QA-10 多轮 session · 独立测试报告

## 1. 元信息

| 项 | 内容 |
|---|---|
| 被测票号 | Ticket 10（sessionId + RewriteStep 多轮补全 + 环比如实返回） |
| 被测版本 | `bb2749c`（HEAD，Ticket 11 后的基线重归档；`git archive` 固定副本，与工作区隔离） |
| 测试时间 | 2026-10-21 15:48 — 16:05（GMT+8） |
| 运行方式 | 干净副本 `/c/Users/zlf/AppData/Local/Temp/qa1011_head`；JDK 21.0.7（JBR）；`mvn -o -Dmaven.repo.local=d:/maven-repo test` → **129/129**；服务 `java -jar target/datapilot-0.0.1-SNAPSHOT.jar --server.port=18081 --datapilot.llm.provider=stub` |
| 依赖环境 | 元数据库 MySQL 3307 `data_agent`（活库，只读校验）；业务库 StarRocks 9030 `mall_dw`（容器 `sr-ai-analysis`，7 天数据 2026-10-13~10-19）；`llm.provider=stub` |
| 结论 | **不通过**：票面 DoD 6/6 达成，但存在 **1 条 P1（单日题上环比把本期值当上期值，静默错数）**；另有 2 条 P2 |

**为什么要固定副本**：本轮测试期间工作区出现了 14 项未提交改动（`agent/hitl/`、`controller/HitlController`、`TraceSpan`/`TraceStep` 等，属 Ticket 12 在途）。在工作区跑 `mvn -o test` 得 **146 例 / 1 失败**（`TraceStepTest.allSpansWrittenInOneCommit` 期望 11 实得 13），与票面无关；本报告全部结论取自 `bb2749c` 的 `git archive` 副本（**129/129 BUILD SUCCESS**）。

---

## 2. DoD 逐条判定

| # | 验收标准（票面原文） | 判定 | 证据 |
|---|---|---|---|
| 1 | 任务 1：`ask(question, sessionId)` 签名 | **达成** | `POST /api/ask {"question":…,"sessionId":…}` 正常；`sessionId=""` / 300 字符 / 中文+emoji 三种形态均 `status=SUCCESS`（A1/A6/A7） |
| 2 | 任务 2：RewriteStep 取上一轮摘要补全指代/时间 | **达成** | `rewrittenQuestion` 回显补全结果；纯指代「再算一遍」→ 原样沿用上一轮问题且 rows 完全相同（A3）；带新维度「那渠道呢」→ 明确拒绝并给出完整问法示例 |
| 3 | 「那环比呢」补全为「近 7 天 vs 前 7 天」，返回「前 7 天无数据」 | **达成** | `qa/verify_session.py` **16/16 PASS**：本期 69 → 补全为「近7天累计支付订单数是多少，环比：与前 7 天对比」→ `comparisonNote=前 7 天无数据，无法计算环比` |
| 4 | 环比那次查询仍是半开区间、窗口整体前移 | **达成**（窗口题） | 环比 SQL 文本与本期一字不差（日期是 `?` 参数），而本期返回 69、环比返回空 ⇒ 绑定参数必然不同；库内 `(2026-10-05, 2026-10-12]` 实测 `SUM=null`（10-12 空分区），确认前移 7 天；单测 `comparisonDaysFollowLastWindow` 锁 10-05/10-12 |
| 5 | 无 sessionId 的追问不补全、不猜 | **达成** | 无 sessionId / 换 sessionId / `sessionId=""` 三种追问均 `answer=无法识别查询意图，请换个问法`，且 `rewrittenQuestion` 原样透传、`comparisonNote` 为空 |
| 6 | 会话隔离 + 三条上限 | **部分达成** | 隔离：换 sessionId 不串（verify_session 第 10 项）；5 轮上限**实测生效**（A4：连问 6 轮后「再算一遍」跟第 6 轮而非第 1 轮）；TTL 30min 与 500 会话上限只有单测（`expiredSessionIsDropped` / `maxSessionsEvictsLeastRecentlyUsed`），未做长跑实测 |

---

## 3. 用例明细

### 3.1 官方验收脚本（`qa/verify_session.py`）

16/16 PASS（干净副本 18081 与混合构建 18080 两次结果一致）。要点：本期 69、环比补全文本、本期 SQL 与环比 SQL 均含 `dt > ? AND dt <= ?`、`comparisonNote=前 7 天无数据`、同比与换维度两种追问均明确拒绝、`/api/health/session` 字段齐全。

### 3.2 独立对抗探针（`qa_1011_probe.py`，83 项断言）

| 用例 | 期望 | 实测 |
|---|---|---|
| A1 `sessionId=""` 首次提问 | 能出数 | SUCCESS，`paid_order_cnt=69` |
| A1 `sessionId=""` + 「那环比呢」 | 不补全、不猜 | 透传原句、`comparisonNote` 空、`answer=无法识别…`（`status=FAILED`，见 4.2 P2-3） |
| **A2 单日题 + 「那环比呢」** | 环比要么前移窗口、要么明确说不可用 | **`本期：53042.41；环比：前 7 天：53042.41`** —— 本期值被当成上期值，见 4.1 P1-1 |
| A3 纯指代「再算一遍」 | 沿用上一轮 | `rewrittenQuestion=近7天累计支付订单数是多少`，`rows=[{paid_order_cnt:69}]` 与上轮一致 |
| A4 连续 6 轮（上限 5） | 淘汰最旧 | 第 6 轮后「再算一遍」→「10月19日GMV最高的省份是哪个」 |
| A5 环比连问两次 | 不 5xx、不套娃 | 两次 `comparisonNote` 均为「前 7 天无数据，无法计算环比」 |
| A6/A7 sessionId 形态 | 不崩 | 300 字符 / 中文+emoji 均 SUCCESS |

### 3.3 库内事实核对（只读）

```
ads_trade_overview_di: 2026-10-13..2026-10-19 共 7 行；dt=2026-10-12 行数=0
SUM(total_gmv) WHERE dt>'2026-10-05' AND dt<='2026-10-12'  → NULL（真·空窗口）
SUM(total_gmv) WHERE dt>'2026-10-12' AND dt<='2026-10-19'  → 390001.84
SUM(total_gmv) WHERE dt='2026-10-19'                        → 53042.41  ← A2 里被标成「前 7 天」的那个数
```

### 3.4 单测（干净副本 129/129）

新增/相关：`SessionStoreTest` 7 例（unknownSession / appendAndReadBack / maxRoundsDropsOldest / expiredSessionIsDropped / maxSessionsEvictsLeastRecentlyUsed / sessionsAreIsolated / blankSessionIsIgnored）、`RewriteStepTest` 7 例（含 `comparisonDaysFollowLastWindow`）、`IrToSqlConverterTest` 新 2 例。

---

## 4. 缺陷清单

### 4.1 P1

| 编号 | 问题 | 位置 | 复现/证据 |
|---|---|---|---|
| **P1-1** | **单日题（`dt = ?`）上追问「那环比呢」，环比查的还是同一天，把本期数字当成「前 7 天」说出来 —— 静默错数。** 根因两处叠加：①`RewriteStep` 无条件 `setComparisonDays(windowDaysOf(last))`，而 `windowDaysOf` 在上一轮没有相对窗口时**兜底成 7 天**（`DEFAULT_WINDOW_DAYS`），于是「与前 7 天对比」这个前提是凭空来的；②`AgentEngine.copyWithOffset` 只在 `ir.getTimeRange() != null` 时平移窗口，单日题的日期在 `filters`（`dt EQ 2026-10-19`）里，原样保留 ⇒ 环比 SQL 与本期 SQL 完全等价 | `agent/step/RewriteStep.java:77-83`（无条件环比）+ `:107-110`（null→7 兜底）；`agent/AgentEngine.java:387-413`（runComparison）、`:416-435`（copyWithOffset 只挪 timeRange） | 会话 `qa10-a2-single`：Q1=53042.41 → 「那环比呢」→ `answer=本期：53042.41；环比：前 7 天：53042.41`。库内 `(10-05,10-12]` 为空、10-12 无分区 ⇒ 若真前移应得「无数据」。`stub_ir/q1.json` 结构为 `filters:[{field:dt,op:EQ,value:2026-10-19}]`，`timeRange` 缺失 |

> 为什么是 P1 而不是 P2：这是「数字看着对、实际是把本期当上期」的错，用户在页面上看到的是两个相等的数，会直接读成「环比持平」。项目自己在 RewriteStep 注释里写过「静默错数比拒绝严重得多」，这条正是该注释要防的场景，只是没覆盖到单日题。

### 4.2 P2

| 编号 | 问题 | 位置 | 证据 |
|---|---|---|---|
| **P2-1** | 环比窗口平移**在外部完全不可观测**：`comparisonSql` 只有 SQL 文本（日期是 `?`），没有 `comparisonParams`，也没有 comparison 的 span。`verify_session` 那条「环比 SQL 与本期同模板（只有窗口不同）」是**纯文本比较，恒真**——即使窗口一格没动它也过 | `controller/AskResponse.java`（无 comparisonParams）、`qa/verify_session.py:88` | 实测两条 SQL 文本完全相同（本期 69 / 环比空），判据无法区分「挪了」和「没挪」 |
| **P2-2** | `windowDaysOf` 的「无窗口兜底 7 天」没有来源标注：上一轮若是单日/按天查询，这个 7 既不是用户的窗口也不是数据的窗口。与 P1-1 同源，单独列出是因为即使 P1-1 修好了，这个兜底仍会让「同比」提示语报出「库内数据只有最近 7 天」这类看似有据、实则抄默认值的文案 | `agent/step/RewriteStep.java:107-110` | 代码路径；A2 复现 |
| **P2-3** | `status` 口径与注释不一致（与 QA-11 P2-1 同条，此处只记 Ticket 10 侧的影响）：「无法识别查询意图」「暂不支持同比」这类**明确拒绝**落 `FAILED`，而 `AskResponse.status` 注释写的是「REJECTED（闸门或改写明确拒绝）」 | `agent/trace/RunStatus.java:38-50`、`controller/AskResponse.java:26-33` | 实测 4 类拒绝（无上下文追问/同比/换维度/未命中）全部 `FAILED`；公开桩配置下 `REJECTED` 几乎不可达 |

---

## 5. 数据清理

| 项 | 结果 |
|---|---|
| 元数据基线 | `GET /api/health/metadata` = `metricCount=13, fieldCount=28, gateConfigCount=4`，与测试前一致（未做任何写操作） |
| 业务库 | StarRocks `mall_dw` 只读查询，无写入；`ads_*` 行数仍为 7 / 21 / 35 / 21 |
| 会话 | 进程内存，服务停止即清空（`storage=memory`）；测试期间共存 8 个会话，随进程退出消失 |
| 元数据库 | 只能增：`agent_run` 960 行 / `agent_step` 8808 行（累计值，含本轮与开发自测；这是 Ticket 09 埋点的设计行为，未删改） |
| 代码/仓库 | 未改动工作区任何文件；`eval/result.json` 用的是 `--out` 到临时路径，未覆盖仓库里的基线 |
| 临时进程 | 18080（混合构建）、18081（干净副本）、18082/18083（deepseek/qwen 端点探针）全部已停止 |

---

## 6. 修复建议

1. **P1-1（必修）**：把「上一轮有没有相对窗口」变成环比的**前置条件**，两条路选一条，别两端都改：
   - 首选在 `RewriteStep`：`Round.windowDays` 为 `null` 时不要兜底，直接回「上一轮是按天查询（`dt = ?`），环比需要「近 N 天」这类窗口」，不设 `comparisonDays`；
   - 兜底在 `AgentEngine.runComparison`：`ir.getTimeRange() == null` 时直接给 `comparisonNote=环比不可用：上一轮没有相对窗口`，不执行第二次查询。
   两层都拦的价值：前者管「用户看得见的文案」，后者管「将来别的入口（HITL/前端）绕过 RewriteStep 也进不来」。
2. **P2-1**：响应加 `comparisonParams`（两个日期），或给 comparison 那次查询加一个 `comparison` span 落库。有了参数，`verify_session` 才能把「SQL 同模板」升级成「参数 = anchor-14 ~ anchor-7」，也才能覆盖 P1-1 这类「窗口没挪」的回归。
3. **P2-2**：`DEFAULT_WINDOW_DAYS` 只用于「口径对齐评测集」这一种已知场景，让它显式出现在 `Round.windowDays` 里（上一轮生成 SQL 时就把实际窗口天数记下来），而不是在读的时候临时兜底。
4. **P2-3**：`RunStatus.of` 增加一个入口标记（例如 `AgentContext.rejectedBy`：`rewrite` / `converter` / `gate`），把「改写明确拒绝」映射到 `REJECTED`；否则「拒绝率」这个指标永远为 0，全被算进失败率。

---

## 7. 收口复验（2026-10-21 16:40，`fb891a6`）

复验在 `git archive db280a6` 固定副本（`java -jar`，端口 18090）上做，判据只有 HTTP 响应体。

| 本报告条目 | 判定 | 证据 |
|---|---|---|
| **P1-1 单日题环比把本期当上期** | **已修复** | 单日题（`10月19日当天的总GMV是多少`，`rows=53042.41`）后问「那环比呢」→ `status=REJECTED`、`answer=上一轮是按天查询（dt = 某一天），环比需要「近 N 天」这类时间窗口，请直接问「近 7 天比前 7 天的…」`；`comparisonSql` 为 `None`、`comparisonRows` 为空 —— **没有**再出现 `环比：前 7 天：53042.41` |
| **同源入口（DETAIL 题，无 timeRange）** | **已修复** | `华东地区有哪些省份` 后问「那环比呢」同样 `REJECTED`，不给数 |
| **双层拦截** | 属实 | `RewriteStep` 不再把“上一轮无窗口”兜底成 7 天（`DEFAULT_WINDOW_DAYS` 已删）；`AgentEngine.runComparison` 保留 `timeRange == null` 兜底（我用 HITL 的 execute 路径也走不到第二次查询） |
| **P2-1 环比参数不可观测** | **已修复** | 响应新增 `comparisonParams`：本期 `['2026-10-12','2026-10-19']`、环比 `['2026-10-05','2026-10-12']`，各差 7 天；`comparisonSql` 仍是 `dt > ? AND dt <= ?` 半开区间 |
| **P2-3 `RunStatus` 无“谁拒的”标记** | **已修复** | `AgentContext.reject(reason, by)` 成对写入，`RunStatus.of` 认 `rejectedBy`；实测「那同比呢」「那渠道呢」→ `REJECTED`（此前全落 FAILED） |
| **P2-2 `DEFAULT_WINDOW_DAYS` 兜底** | 已按建议删除，改为显式拒绝 | 同上 |
| **回归** | 通过 | `verify_session` **21/21**（脚本已加“参数各差 7 天”判据）、`verify_traces` 31/31、`mvn -o test` 152/152 |

**结论：QA-10 的 1 条 P1 + 3 条 P2 全部收口，可以从「不通过」改判为通过。** 附带把 P2-3 的收益扩散到了 Ticket 11 的 `status` 语义（明确拒绝 → REJECTED，执行故障 → FAILED），见 `QA-11-解释与报告-测试报告.md` 第 7 节。
