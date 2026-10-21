# QA-06c 单元测试三件套 · 独立测试报告

## 1. 元信息

| 项 | 内容 |
|---|---|
| 被测票号 | Ticket 06c（单元测试三件套）+ 同 commit 内的 QA-06a 五条 P2 收口 |
| 被测版本 | `7b23c58`（06c 本体）；`c040e4d`（当前 HEAD，含 07） |
| 测试时间 | 2026-10-21 14:05 — 14:40（GMT+8） |
| 运行方式 | 固定副本 `git archive 7b23c58 \| tar -x -C /tmp/dp06c`；JDK 21（pom `java.version=17`，release 17 编译）；`mvn -o -Dmaven.repo.local=d:/maven-repo test` |
| 依赖环境 | 单测本身**不连库**；`check_golden_set.py` 的 replay 需 StarRocks 9030（`sr-ai-analysis`，3.3.8-e3816ec） |
| 基线有效性 | `git diff 7b23c58..c040e4d -- src/test/` 只有 07 新增的 3 个类，06c 的 3 个测试类一字未改，结论对 HEAD 仍成立 |
| 结论 | **PASS**（DoD 4/4 达成）；1 条 P1、8 条 P2 |

**关于基线隔离**：测试开始时工作区已被 Ticket 07 的改动占用（`EngineJdbc`/`JdbcSqlExecutor` 等），中途又出现 Ticket 08 的未提交改动（`DatapilotProperties`、`HealthController` 修改，`DeepSeekLlmClient` 删除）。全部验证都在 `/tmp` 固定副本内完成，`Desktop/DataPilot` 工作区零写入。

---

## 2. DoD 逐条判定

| # | 验收标准（票面原文） | 判定 | 证据 |
|---|---|---|---|
| 1 | `mvn test` 全绿 | **达成** | `Tests run: 31, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS，8.99s |
| 2 | 3 类 ≥9 用例 | **达成** | Converter 11 / GateChain 11 / TimeRange 9 = 31；`grep -c @Test` 与 surefire 计数逐类一致；无 `@Disabled`/`@Ignore` |
| 3 | 任务 1：`=` 1 占位符 / `IN` n 个 / `LIKE` 包 `%` | **达成** | `eqBindsSinglePlaceholder` 断言 `WHERE dt = ?` + 1 参 + SQL 里不含字面量；`inBindsNPlaceholders` 3 值 → 3 个 `?`；`likeWrapsPercent` → `%键盘%` + 1 个 `?`；另有 `inRejectsCommaJoinedString` 显式拒绝 |
| 4 | 任务 2：五道闸各 1 个 reject | **达成（1 条偏差，理由成立）** | 存在性/权限/代价/语法各 1 个 reject，语法另有 1 个「非法 SQL 报语法错误」；闸门⑤按 `GateResult` 契约只 `ok+warning` 不拒绝，用例改为「不 reject + warning 穿透 GateChain」，与 `GateResult` 类注释完全一致（前四道 reject、第五道 ok+warning） |
| 5 | 任务 3：10-13 命中、10-12 不命中 | **达成** | `sevenDaysIsHalfOpenInterval` 断言 `params=[2026-10-12, 2026-10-19]`、含 `dt > ?`/`dt <= ?`、不含 `BETWEEN`、不含 `dt >= ?`、不含 `dt < ?` |
| 6 | 测试要求：断言外部行为，不测实现细节 | **基本达成** | 断的是 SQL 文本形状/占位符数/参数列表/拒绝文案；仅 2 处偏实现（见 P2-6）。SQL 形状属对外契约，可接受 |
| 7 | 修改范围：pom 加 `spring-boot-starter-test` | **达成** | test scope；本地仓库已有依赖，`mvn -o` 离线跑通（证明「换机器需联网一次」只在首拉） |

### 偏差裁定

票面「五道闸各 1 个 reject 用例」第 5 条**确实不成立**，不是实现偷懒：`GateResult` 的字段设计（`pass/reason/warning`）与类注释都明确「结果自检通过但可疑时返回 ok + warning（不拒绝）」。若强行写一个 reject 用例，只能去测一个不存在的契约。改成 warning 穿透断言后，恰好覆盖 QA-05 P2-1 那个「`runPhase` 一律 return ok()，warning 永远到不了 AgentEngine」的回归点，比原票面更有价值。

---

## 3. 用例明细（独立执行）

### 3.1 能力验证

| # | 验证项 | 方法 | 结果 |
|---|---|---|---|
| T1 | 用例数真实性 | `grep -c @Test` × surefire 逐类比对 | 11/11/9，一致，无跳过 |
| T2 | 离线可跑 | `mvn -o test` | PASS（9s） |
| T3 | 不连库 | 读测试代码：全 `mock(OntologyCache/MetadataRepository/JdbcTemplate)`，无 `@SpringBootTest` | 成立 |
| T4 | 白名单真按 seed 填 | `TestOntology.tableFields()` 与 `schema.sql` 逐表比对 | 4 张表字段全对；`orders` 未纳入（单测不涉及） |
| T5 | 变异测试 15 组（独立副本） | 见 3.2 | **15/15 符合预期** |

### 3.2 变异测试（我自己写的 15 组，每组一份独立副本）

> 每组：`copytree` → 单点改造 → 清空继承的旧 surefire 报告 → `mvn -o test` → 读 surefire txt 取实际变红用例。

| 变异 | 改了什么 | 期望 | 实际 | 变红的用例 |
|---|---|---|---|---|
| M01 | `dt > ?` → `dt >= ?` | 红 | 红 | `sevenDaysIsHalfOpenInterval`（+2 个连带） |
| M02 | 同表复合去掉 `NULLIF` | 红 | 红 | `sameTableCompositeUsesNullIfZero` |
| M03 | 代价闸门不数分母表 | 红 | 红 | `costCountsDenominatorTableAsJoin` |
| M04 | `GateChain` 丢弃 warning | 红 | 红 | `chainPropagatesWarningFromResultPhase`、`chainMergesMultipleWarnings` |
| M05 | `IN` 逗号串静默切分 | 红 | 红 | `inRejectsCommaJoinedString` |
| M06 | 去掉「单行全 null」分支 | 红 | 红 | `resultCheckTreatsAllNullSingleRowAsEmpty` |
| M07 | 分母子查询再取一次锚点 | 红 | 红 | `crossTableCompositeRequestsAnchorOnce` |
| M08 | 分母/主查询参数**整体换位**（值相同） | 绿 | **绿** | —（证实 U2 不可观测） |
| M09 | `from`/`anchor` 参数写反 | 红 | 红 | `crossTableCompositeParamOrderMatchesPlaceholders` + TimeRange 3 例 |
| M10 | `LIMIT` 固定 100 | 红 | 红 | `sameTableCompositeUsesNullIfZero` |
| M11 | warning 合并分隔符 `；`→`;` | 红 | 红 | `chainMergesMultipleWarnings` |
| M12 | 权限闸门不查表白名单 | 红 | 红 | `authorityRejectsTableOutsideWhitelist` |
| M13 | 存在性闸门不查指标白名单 | 红 | 红 | `existenceRejectsUnknownMetric`、`chainStopsAtFirstReject` |
| M14 | 语法闸门不查表白名单 | 红 | 红 | `syntaxRejectsTableOutsideWhitelist` |
| M15 | `from = anchor - (days-1)` | 红 | 红 | `oneDayOnlyCoversAnchor`、`sevenDaysIsHalfOpenInterval` 等 5 例 |

**M08 是最有价值的一条**：把「分母参数先、主查询参数后」整段互换后测试**仍绿**，因为两组值恒为 `[from, anchor]`，互换后序列一模一样。这独立证实了交付里 U2 的披露是真的，不是找借口。**第一次跑 M08 时我做成了「值序列真的变了」**（`remove(3)` 后索引偏移），测试立刻红——说明 `containsExactly` 对参数顺序是有效的，只是分不清「同值两组的归属」。

### 3.3 QA-06a 五条 P2 复验

在 `c040e4d` 副本上直接跑 `eval/check_golden_set.py`，另做 8 组「改坏副本 → 看是否报」：

| 修复项 | 验证方法 | 结果 |
|---|---|---|
| P2-1 sources 只写不校验 | 干净副本默认跑 | **报漂移 2 条、exit=1**（详见 P1-1）；`sources={}` 时报「sources 缺失：无法证明这份 golden_set 对应哪版文档」exit=1 |
| P2-2 不带 --replay 放行漂移 | Q1 值 +0.02 | 默认 → exit=1「Q1 scalar 的 expect_value=53042.43 ≠ expect_rows 首格 53042.41」；`--no-replay` → **仍抓**（文件内自洽校验，逃生口只放弃连库，比交付描述更严） |
| P2-3 rowset 不校验形状 | Q1 改成 rowset | exit=1 |
| P2-4 删 window 不报错 | 删 Q2 的 window | exit=1，文案「窗口题集合 [11,12,13,14] ≠ 期望 [2,11,12,13,14]（少一条就少一题 SQL 形状断言）」 |
| P2-5 row_order_stable 事实错误 | 读结构 | 逐题字段已删；`target.row_order_sampling = {rounds:2, unstable:[8], stable:[其余14]}`，含「仅证据不作判据」说明 |
| 逃生口 | `--allow-stale` | exit=0，降级为告警 |

### 3.4 06b 归档与 dirty_files

| 验证项 | 结果 |
|---|---|
| `eval/result.json` 元信息 | `golden_set.sha256=655695b5…`（与文件实算一致）、`git_head=862e2e5`、`dirty_files=0`、`health=13/28/4` |
| dirty_files 排除自身输出 | 副本 `git init` 后：干净 → `dirty=0`；追加一行注释到 `SqlValidator.java` 再跑 → `dirty=1`（此时 `git status` 有 2 个文件，`eval/result.json` 被排除）→ 修复生效 |

---

## 4. 缺陷清单

### P0
无。

### P1

**P1-1｜干净仓库上 `check_golden_set.py` 直接 FAIL（exit=1），来源指纹两处漂移**

- 位置：`eval/golden_set.json` → `sources.questions_and_expect_sql.sha256` / `sources.sql_must_and_window.sha256`
- 现象（`c040e4d` 原样副本，未做任何改动）：

```
来源指纹  2 处漂移（--allow-stale 降级为告警）
== FAIL 2 条 ==
  - 来源已漂移：docs/评测集.md 记录 6abbf84e0592，实际 1eb75d3ab0f2
  - 来源已漂移：qa/eval_expectations.json 记录 ed4987a0067e，实际 5c9d3ecf0741
EXIT=1
```

- 归因：`gen_golden_set.py` 的 `sources` 是实时 `sha256_full()` 算的，说明这份 `golden_set.json` 的 15 条数据被重生成过、但 `sources` 两行没跟着重算（很可能是手工改了 P2-3/P2-5 那几处字段后没跑 `gen`）。交付里「真源重生成后 sha256 655695b5」说的是**文件自身哈希**（核对一致），不是 sources 里那两个来源指纹。
- 影响：评审/CI 第一条命令就红；而它恰是 06b 的「文档改了就报」防线，红了以后没人会认真看，防线退化。
- 讽刺点：这条报错正是 P2-1 修复**生效**的证据——校验器现在真有两处不一致可报，只是基线自己没同步。

### P2

| # | 位置 | 问题 | 证据 |
|---|---|---|---|
| P2-1 | `check_golden_set.py` `sources` 校验 | 只校验「登记了的条目是否漂移」，不校验「该有的条目是否齐全」；把两个 sources 全删才报错，删其中一个不报（`H_drop_source` 只剩 docs 的漂移 1 条） | 删 `sql_must_and_window` 后 exit=1 仅因另一条漂移，不是因缺条目 |
| P2-2 | `IrToSqlConverterTest.crossTableCompositeParamOrderMatchesPlaceholders` | `indexOf("(SELECT") < lastIndexOf("WHERE dt > ?")` 只能证明子查询文本在前，不能证明参数归属；真正的「组间归属」在本设计下不可测（M08 已证） | 见 3.2 M08 |
| P2-3 | `IrToSqlConverterTest` 全等断言 | `sameTableCompositeUsesNullIfZero` 用整条 SQL 字符串全等，格式化改动即红（脆弱），与「只测外部行为」有张力 | M10（LIMIT 改 100）能红，说明断言紧 |
| P2-4 | `TimeRangeTest.inWindow()` | 测试里自己重写了一遍半开区间语义（`d.isAfter(from) && !d.isAfter(anchor)`），「10-12 不命中」是从参数推导而非执行 SQL；真正的符号锁靠 `doesNotContain("dt >= ?")` | 代码 150-156 行 |
| P2-5 | `GateChainTest.chainMergesMultipleWarnings` | `@DisplayName` 写「三道闸门都带 warning」，实际只 stub 了两道 | 代码 188-199 行 |
| P2-6 | `TestOntology` | `max_metric=1`、`max_join=1`，与真实 `gate_config` 的阈值不同（活库 `max_metric` 不是 1）；`costRejectsTooManyMetrics` 断言文案 `"指标数 2 > 1"` 绑死在替身上 | 单测自洽，但与线上阈值脱钩 |
| P2-7 | 交付描述 | 「单测 31 个不连库，评审机没 MySQL 也能跑」成立；但 `mvn test` 在无库机器上会因 07 的 `EngineSwitchTest` 报 47 绿 + 4 skip（`skipped` 不为 0），交付里 `skipped=0` 只对本机成立 | 见 QA-07 报告 §3 |
| P2-8 | `pom.xml` | `java.version=17`，但票面落地记录写「JDK 17」而本机用 JDK 21 的 `jbr` 跑；`release 17` 能编译，但 Mockito self-attaching 告警只在高版本 JDK 出现，评审机用 JDK 17 时行为不同（不影响结果） | 构建日志 |

---

## 5. 数据清理

| 项 | 状态 |
|---|---|
| 隔离库 | 本票**未建库**（单测不连库）；06a 复验的副本只读 `docs/评测表.md` 等文件，不改库 |
| 临时文件 | `/tmp/mut06c/*`（15 份变异副本）、`/tmp/ck07/*`（9 份校验副本）—— 均在 `/tmp`，不在仓库；已核对 `git status` 无新增 |
| `stub_ir` | 未注入临时桩（本票无端到端需求） |
| 端口 | 未占用 |
| 活库 `data_agent` | 零写入；测试后 `13/28/4/4/16/0/12`（metric/field/gate/semantic_model/metric_table/orders/dimension） |
| 仓库 | `Desktop/DataPilot` 工作区在测试期间被 Ticket 08 改动占用（非本报告操作），基线 commit `c040e4d` |

---

## 6. 修复建议（按性价比排序）

1. **P1-1**：`python eval/gen_golden_set.py` 重跑一次，把 `sources` 两行刷成当前哈希；顺手在 06b 票面里写死「改 golden_set 任何字段后必须跑 gen，否则 `check` 必红」。改完把 `check_golden_set.py` 挂到 CI 第一步，否则这套校验就是自己跟自己较劲。
2. **P2-1**：`sources` 改为「必须有 `questions_and_expect_sql` 与 `sql_must_and_window` 两个键，缺一即 FAIL」。一行集合比对，比现在「缺了就不校验」正确得多。
3. **P2-3**：`sameTableCompositeUsesNullIfZero` 的整条 SQL 全等改成「含 `ROUND(` / `NULLIF(` / `, 0), 4)` + 列数 1 + 参数 1 个」，避免 LIMIT 之类的无关改动把它弄红。
4. **P2-4**：`inWindow()` 保留没问题，但 `@DisplayName` 里「10-12 不命中」应写成「参数推导下 10-12 不在窗口内」，别让人以为真的查了库。
5. **P2-5**：文案改「两道」。
6. **P2-6**：在 `TestOntology` 顶部注明「阈值为测试专用，非线上 gate_config 真值」，避免后来人拿它当口径基线。
