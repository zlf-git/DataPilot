# Ticket 10：多轮 session

## 目标
ask 加 sessionId，RewriteStep 补全指代，环比如实返回「前 7 天无数据」。

## 背景
来自 A2（Spec §3 阶段③）+ DoD4。

## 修改范围
- AskController：sessionId
- RewriteStep：多轮补全
- AgentContext：session 存储

## 任务
1. ask(question, sessionId) 签名
2. RewriteStep 取最近 2 轮 + 上轮 IR 摘要补全指代/时间
3. 环比场景如实返回「前 7 天无数据」

## 验收标准
「那环比呢」补全为「近 7 天 vs 前 7 天」，返回「前 7 天无数据」。

## 测试要求
集成测试：连续两问，断言第二问基于上下文补全 + 环比如实返回无数据。只测外部行为。

## 依赖
Ticket 01 + Ticket 04

## 预估
0.5 天

## 不包含
- 造 14 天数据让环比有真结果（Spec 明确 mall_dw 仅 7 天）

---

## 落地记录（2026-10-21）

**做了什么**

| 位置 | 内容 |
|---|---|
| `agent/session/SessionStore.java`（新增） | 进程内存的多轮上下文：单会话最多 5 轮、TTL 30 分钟（按最后一轮写入时间算）、会话总数上限 500（`accessOrder` 淘汰最久未用）。三条上限都来自 `datapilot.session.*`，启动日志打印实际值 |
| `agent/step/RewriteStep.java` | 多轮补全：环比沿用上一轮指标/维度/窗口天数，补成「&lt;上一轮问题&gt;，环比：与前 N 天对比」；纯指代（「再算一遍」）等价于上一轮问题直接沿用；**带新维度/新时间的追问显式拒绝**；同比显式拒绝 |
| `ir/TimeRange.java` + `sql/IrToSqlConverter.java` | `compareOffsetDays`：只由服务端填（模型不产出），把窗口整体前移 N 天，仍是半开区间 `(anchor-offset-days, anchor-offset]` |
| `agent/AgentEngine.java` | ⑩ 环比：复制 IR（只换窗口）→ 转换 → 执行「前 N 天」窗口，结论并进 answer；⑪ 记一轮会话（只记真的生成了 SQL 的轮次） |
| `controller/AskResponse.java` | 新增 `rewrittenQuestion` / `comparisonSql` / `comparisonNote` 三个回显字段 |
| `controller/HealthController.java` | `GET /api/health/session`：存活会话数 + 三条上限 + `storage=memory` |

**验收（票面「那环比呢 → 近 7 天 vs 前 7 天，返回前 7 天无数据」）**

`qa/verify_session.py` **16/16 PASS**，全部断言外部行为：

```
第一问「近7天累计支付订单数是多少」+ sessionId → 69（半开区间 SQL）
追问「那环比呢」→ rewrittenQuestion = 近7天累计支付订单数是多少，环比：与前 7 天对比
                  本期 69（SQL 与本问完全一致）
                  comparisonSql 同模板、仍含 dt > ? 与 dt <= ?
                  comparisonNote = 前 7 天无数据，无法计算环比
                  answer = 本期：69；环比：前 7 天无数据，无法计算环比
无 sessionId 追问 → 无法识别（没上下文不猜）
换 sessionId 追问 → 无法识别（会话之间不串）
同比 → 暂不支持同比：库内数据只有最近 7 天，没有去年同期数据
「那渠道呢」→ 多轮追问暂只支持环比…请把条件说全
```

实测依据：`ads_trade_overview_di` 的 `dt` 只有 `2026-10-13 ~ 2026-10-19`（7 天），
前 7 天窗口 `(2026-10-12, 2026-10-05]` 落在数据之外 —— 所以「前 7 天无数据」是**真查出来的空**，
不是写死的文案。单测侧用 `comparisonOffsetShiftsWindowKeepingShape` 锁住参数确实前移了 7 天
（`2026-10-05 / 2026-10-12`），避免「文案说挪了、SQL 没挪」这种假绿。

**测试**：`mvn -o test` **116/116**（新增 `SessionStoreTest` 7 + `RewriteStepTest` 7 + 转换器 2）；
回归 `run_eval` PASS（15/15、49/49、探针 PASS、平均延迟 55.9ms）、`e2e_stub` PASS、`verify_traces` 31/31。

**票面偏差（2 条，都是把范围收窄，不是缩水）**

1. **补全只覆盖「能确定语义」的追问**。票面写「取最近 2 轮 + 上轮 IR 摘要补全指代」，实现上：
   环比 → 补全并跑第二窗口；纯指代 → 沿用上一轮；**带新维度/新时间的追问（「那渠道呢」）直接拒绝**
   并提示完整问法。理由：这类追问靠字符串拼接只能拼出「上一轮问题 + 一句新话」，桩/LLM 很可能
   只答上一轮 —— 那是静默错数，比明确拒绝严重。要做真正的换维度追问，需要 LLM 侧的改写能力
   （Ticket 11 接真实模型后再评估），不是 0.5 天的活。
2. **同比不做**。库内只有 7 天数据，同比需要去年同期；显式拒绝，不编数。

**已知边界**

- **会话在进程内存**：重启即清空。这是有意的降级——每轮写一次元数据库会给主链路加一次 fsync
  （Ticket 09 把延迟从 231ms 拉回 56ms 就是同一个原因）。单机单用户的场景下，丢上下文的代价
  只是「再问一遍」。
- **环比那次执行不再单独过语法闸门**：SQL 与本期只差两个日期参数，表/字段已被本轮闸门校验过，
  参数化绑定仍在。若以后环比要换指标/维度，就必须让它重新走一遍闸门。
- 环比只有「前一个等长窗口」，不支持「上月同期」「年初至今」这类任意基准。

---

## 10b 补丁：QA-10 收口（2026-10-21）

QA-10 判 **不通过**：DoD 6/6 达成，但 **1 条 P1（单日题上环比把本期值当上期值，静默错数）** + 3 条 P2
（报告在 `QA-10-多轮session-测试报告.md`）。P1 与 P2 全部在本补丁处理。

### P1-1（必修）

**现象**：上一轮是单日题（`10月19日当天的总GMV是多少`，日期在 `filters` 里、没有 `timeRange`）时追问
「那环比呢」，回答是 `本期：53042.41；环比：前 7 天：53042.41` —— 把本期数字当成上期讲了出来，
用户会读成「环比持平」。

**根因两处叠加**（报告定位准确）：

1. `RewriteStep` 无条件 `setComparisonDays(windowDaysOf(last))`，而 `windowDaysOf` 在上一轮没有相对窗口时
   **兜底成 7 天**（`DEFAULT_WINDOW_DAYS`）—— 「与前 7 天对比」这个前提是凭空来的；
2. `AgentEngine.copyWithOffset` 只挪 `ir.timeRange`，单日题的日期在 `filters` 里，原样保留 ⇒
   环比 SQL 与本期 SQL 完全等价。

**修法（两层都拦，报告建议的两条路各拦一层）**：

| 层 | 位置 | 行为 |
|---|---|---|
| 用户可见文案 | `RewriteStep` | `last.windowDays() == null` 时**不兜底**，直接拒绝：「上一轮是按天查询（dt = 某一天），环比需要「近 N 天」这类时间窗口…」。`DEFAULT_WINDOW_DAYS` 常量删除 |
| 兜底硬闸 | `AgentEngine.runComparison` | `ir.getTimeRange() == null` 时**不执行第二次查询**，`comparisonNote=环比不可用：上一轮没有相对时间窗口（按天查询）…`。防将来别的入口（HITL/前端）绕过 RewriteStep |

回归锁：`RewriteStepTest.comparisonOnSingleDayQuestionIsRejected`（断言 `comparisonDays` 为 null、
`rewrittenQuestion` 为 null、`rejectedBy=rewrite`）+ `verify_session.py` 新增 4 项端到端断言
（`comparisonSql` 为空、「前 7 天」不出现在 `comparisonNote`、`status=REJECTED`）。

### P2 三条

| 报告条目 | 根因 | 落地 |
|---|---|---|
| P2-1 环比窗口平移在外部不可观测 | `comparisonSql` 只有 SQL 文本（日期是 `?`），本期与环比**逐字相同**；`verify_session` 那条「同模板」是纯文本比较，恒真 | `AskResponse` 新增 **`comparisonParams`**（`AgentContext.comparisonParams`）。`verify_session` 把判据从「SQL 文本相同」升级为「参数恰好各差 7 天」：实测 `base=[2026-10-12, 2026-10-19]` / `comp=[2026-10-05, 2026-10-12]` |
| P2-2 「无窗口兜底 7 天」没有来源 | 同 P1-1 根因①，且同比文案会引用这个默认值当库内事实（「库内数据只有最近 7 天」） | 兜底删除；同比文案改为「库内没有去年同期数据，无法计算同比」，不再引用任何窗口天数。`RewriteStepTest.yearOverYearIsRejectedWithReason` 加 `doesNotContain("最近 7 天")` |
| P2-3 明确拒绝落 FAILED | `RunStatus.of` 只认「闸门结果里有 `=REJECT`」，改写拒绝没有闸门记录 | `AgentContext.reject(reason, by)`：一次写「文案 + 谁拒的」，不提供裸 setter（有文案没标记就会静默落 FAILED）。`RunStatus.of` 见 `rejectedBy` 即 REJECTED。写入点三处：`rewrite`（同比/环比无窗口/换维度）、`identify`（无法识别）、`converter`（跨表维度等生成器拒绝）。LLM 超时/服务异常仍是 FAILED |

**实测**：`mvn -o test` **152/152**；`verify_session` **21/21**（新增环比参数、单日题环比拒绝各一组）；
`run_eval` PASS（15/15、49/49、探针 PASS、延迟 46.8ms）；`e2e_stub` PASS；`verify_traces` 31/31。

**没动的地方**：`Skill`/`SessionStore` 的 5 轮上限、TTL、会话数上限都没改 —— 报告里「TTL 与 500 会话
只有单测」这条属于测试覆盖取舍（长跑实测成本高于收益），不列为缺陷处理。
