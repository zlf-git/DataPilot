# QA-16 文档与 seed 收口 — 独立测试报告

## 1. 元信息

| 项 | 内容 |
|---|---|
| 被测票号 | Ticket 16：文档与 seed 收口（D1 编号统一 / C4·C5 口径 / G1 自检阈值 / E2 验收公式 + Ticket 07 交接的巡检） |
| 被测 commit | **`589f74e`**（本体 `660b3cb`，基线重归档 `589f74e`；工作区 HEAD `03c52d4` 未触及本文档与代码） |
| 被测物形态 | `git archive 589f74e` 固定副本；文档/seed 直接读副本原文，`/api/health/drift` 与口径行为打到真实进程 **18097** |
| 工作区状态 | 工作区干净、`ahead=0` |
| 测试时间 | 2026-10-21 22:24 — 22:32（GMT+8） |
| 依赖环境 | StarRocks 9030 `mall_dw`（只读，4 张 ADS 表）、MySQL 3307 `data_agent`、`provider=stub` |
| 结论 | **通过**：票面 4 项任务、1 条验收、「grep 校验」测试要求全部达成；`mvn -o test` **207/207**（含 `ResultCheckGateTest` 6、`SchemaDriftTest` 5）、`qa/check_docs_consistency.py` **25/25**、自写探针 **33/33**、`run_eval` 基线 `git_head=660b3cb` `dirty=0`。新记 **1 条 P2 + 5 条 P3**，无 P0/P1 |

## 2. DoD 逐条判定

| 票面条目 | 判定 | 证据 |
|---|---|---|
| **任务 1** D1 三套编号收敛成一套 | **达成** | `docs/五道闸门.md` 有 `## 闸门①`~`## 闸门⑤` 五个标题 + 「编号约定（唯一编号源…）」段；我用**自己的正则**（`闸门\s*[0-9０-９]` + `\bgate\s*[-_#]?\s*[1-5]\b`）扫 `docs/` 全部 `.md`（排除 `QA-*` 与「编号约定」整段）：**0 处残留**；代码侧 5 个 `Gate.name()` 只返回中文名（`存在性/权限/代价/语法/结果自检`），活响应 `gates` 也全是「中文名=PASS/REJECT」 |
| **任务 2** C4/C5 口径注释（含未支付/跨期不可加） | **达成** | `deploy/seed.sql:7-10` 头部口径块有 `C4④ 商品销售额「含未支付明细」`、`C5② 支付用户数跨期不可加 → 显式拒绝`；`:54` `field_comment` 带「（含未支付明细）」；`:57-60` 指标段两条 ⚠️ 说明；`docs/Ontology定义.md` 新增 `### 6.5` 两句边界表（含「用户问到时怎么答」一列） |
| **任务 3** G1 结果自检阈值写死常量（不进 `gate_config`） | **达成** | `ResultCheckGate` 里 `static final BigDecimal NEGATIVE_VALUE_TOLERANCE = 0.01`、`static final double MAX_NULL_RATIO = 0.2`；`cache.gateThreshold` 全文只出现 **1 次**且读的是 `max_rows`；活库 `gate_config` 的 `result` 组只有 `max_rows`；尝试 `PUT paramKey=null_ratio` / `max_abs_gmv` **都是 400**（白名单挡回），不是「写进去但没人读」 |
| **任务 4** E2 验收公式写死 | **达成** | `docs/评测集.md` `### 3.1 六层指标的分母与口径（写死，Ticket 16 / E2）`：六行定义表，每个指标 `… / 15`；「闸门拦截率 = 被任一闸门 `REJECT` 的题数 / 15，**正常态目标是 0%**」；平均延迟「**含** LLM 调用…不含网络往返/前端渲染/冷启动」+「`provider=stub` 约 55 ms，真模型 1~3 s，两个数分开报，不取平均」 |
| **验收** 文档与 seed 口径一致、无编号冲突 | **达成** | 上述 25 + 33 项机械检查全绿；`check_docs_consistency.py` 的 25 条里包括「`cache.gateThreshold` 只出现 1 次」「seed 里没有 `max_abs_gmv`/`null_ratio`」 |
| **测试要求** grep 校验编号唯一 + 口径注释存在 | **达成** | 官方脚本 25/25；我另用独立正则复核了一遍（3.3） |
| **Ticket 07 交接项**：`listTables()` 要有真实调用方 | **达成** | `SchemaDrift.physicalColumns()` → `executor.listTables()`；`GET /api/health/drift`（`HealthController`）；启动日志实测一行：`SchemaDrift: 语义层 ↔ 物理表一致性巡检通过（4 张表，引擎 starrocks）` |

## 3. 用例明细

### 3.1 官方脚本

| 脚本 | 结果 |
|---|---|
| `qa/check_docs_consistency.py` | **25/25 全绿**（D1 8 项 / C4·C5 8 项 / G1 4 项 / E2 5 项） |
| `mvn -o test` | **207/207**，24 类，其中 `ResultCheckGateTest` 6、`SchemaDriftTest` 5 为本票新增 |
| `eval/run_eval.py` | PASS 15/15、49/49、延迟 **42.3 ms**、拦截率 0/15；仓库里归档的 `eval/result.json` 核对为 `git_head=660b3cb`、`dirty_files=0`、`gateConfigCount=5` |

> 延迟 42.3 ms 与票面归档的 61.7 ms 差 19 ms，属机器负载差异（我这一轮只起两个 JVM），不是回归 —— 票面自己也写了 54.6 → 61.7 的抬升来自 G1 两条自检。

### 3.2 `GET /api/health/drift`：不是常量（人为造红）

| 步骤 | 结果 |
|---|---|
| 基线（匿名 GET） | `consistent=true`，`engine=starrocks`，`ontologyTables` 与 `physicalTables` 都是 4 张 ADS 表，`inOntologyNotInDb=[]`、`inDbNotInOntology=[]`、`columnDrift={}` |
| 管理员 `PUT /api/metadata/fields?mode=create` 加一个**库里不存在**的列 `zz_qa_drift` | 200 |
| 立刻再查 | **`consistent=false`**，`columnDrift.ads_trade_overview_di = {"missingInDb":["zz_qa_drift"],"extraInDb":[]}` → 巡检确实会红，且红的分类正确 |
| `DELETE` 掉探针列 | 200 |
| 再查 | `consistent=true`、`columnDrift={}` 复原 |

这一步是本票最关键的独立证据：若只报「`consistent=true`」，无法区分「真的巡检了」和「写死返回 true」。

### 3.3 独立编号复核（我自己的正则，比官方脚本更宽）

- 扫 `docs/**/*.md`（排除 `QA-*`，并把 `五道闸门.md` 的「编号约定」整段豁免），同时匹配半角/全角数字与英文 `Gate N`：**0 处残留**。
- 中文数字形态（`闸门三`、`闸门第二道`）无法机器判定，原因见 P3-1。

### 3.4 C4/C5 的口径承诺 vs 外部行为

| 用例 | 结果 |
|---|---|
| `POST /api/ask`「近7天累计支付用户数是多少」 | `REJECTED`、`rows=[]`、`answer="无法识别查询意图，请换个问法"` → **没有给出偏大的 SUM**（安全），但**也没有给出文档承诺的口径解释**（见 P2-1） |
| `PUT /api/gate-config` `null_ratio=0.99` | 400「param_key 非法：只允许 [max_metric, max_complexity, max_rows, max_limit, max_join]」 |
| `PUT /api/gate-config` `max_abs_gmv=99999` | 400 |

### 3.5 自查自纠（2 处，未计入缺陷）

1. 第一版编号正则把中文数字也算进去，撞出 4 个假阳性：`五道闸门.md:26/:28`（「不写「闸门 3」」这类**举例说明**）、`14-鉴权最小落地.md:125`（「白名单/指标/闸门**三个页面**」）、`README.md:61`（「Ticket 02 **五闸门一起**」）。改成只认数字 + 整段豁免「编号约定」后归零 —— 是我的判据过宽，不是文档有问题。
2. 第二版又加了 `闸门第[一二三四五]`，撞出 `docs/执行模块.md:172`「执行 | StarRocks 用只读账号 `agent_ro`（**权限闸门第二道**）」—— 这里「第二道」是修饰语不是编号。最终版放弃中文数字，理由写进 P3-1。

## 4. 缺陷清单

### P2-1 C5② 承诺的「显式拒绝」只落在文档，用户拿到的是「无法识别」

- **位置**：`deploy/seed.sql:59-60` + `docs/Ontology定义.md` §6.5 的「用户问到时怎么答」列 —— 两处都写「『近 N 天累计支付用户』当前不支持，生成器应**显式拒绝**而不是返回偏大的数」。
- **实测**：`POST /api/ask {"question":"近7天累计支付用户数是多少"}` →
  ```
  status=REJECTED  rows=[]  answer=无法识别查询意图，请换个问法
  ```
- **问题**：数是对的（没给偏大的 SUM），但**拒绝对用户是误导的**。用户问的是一个口径上「不能这么算」的指标，正确回答是「支付用户数是去重人数，不能跨天相加；要看请按天看」；现在回的是「换个问法」—— 用户换个问法只会换个说法继续问同一个不可加的累计值，下一轮可能就命中 `SUM(paid_user_cnt)` 的模板。
- **另一面**：`Ontology定义.md` §6.5 结尾写「口径改动只改这两处，代码不用动」，这句话与「生成器应显式拒绝」自相矛盾 —— 要让拒绝文案带上口径原因，生成器侧必须有对应分支（现在没有：`stub_ir` 里没有这条，`RewriteStep` 也不识别「累计支付用户」）。

### P3（5 条，记录）

| 编号 | 问题 | 位置 |
|---|---|---|
| P3-1 | 编号检查只能用半角数字。中文数字变体在本仓**必然假阳性**（实测三类：`闸门三个页面`、`五闸门一起`、`权限闸门第二道`），脚本注释里没写这条边界 —— 下一个人想「把中文数字也纳入检查」时会把这三条当残留 | `qa/check_docs_consistency.py:60-72` |
| P3-2 | `SchemaDrift` 只比表/列**存在性**，不比类型、注释、分区键（已披露）。补充一条实测边界：`physicalTables` 直接来自 `listTables()`，**库里多出任何未登记的表**（临时表、别人建的表）都会让 `consistent=false`，共享库上会产生噪声 | `knowledge/SchemaDrift.report()` |
| P3-3 | `/api/health/drift` 匿名可读，暴露表名列名（已披露，与 `/api/health/engine` 同级） | `controller/HealthController` |
| P3-4 | `NEGATIVE_VALUE_TOLERANCE` 对所有数值列生效，将来接入「退款额」这类本就该为负的列会误报（已披露，处置是按列名白名单而非调大容差） | `agent/gate/ResultCheckGate.firstNegativeValue` |
| P3-5 | `seed.sql` 里 `max_complexity` 只 `INSERT IGNORE`，与同文件对 `max_join`/`max_metric` 的强制 `UPDATE` 不一致（Ticket 15 报告 P3-3 同源，本票 seed 是落点，故在此再记一次） | `deploy/seed.sql:142-150` |

## 5. 数据清理

| 项 | 结果 |
|---|---|
| 探针字段 `zz_qa_drift` | 已 DELETE，`field_metadata` 回到 **28** 行，与测试前逐行一致（12 dimension / 16 metric，`ads_trade_overview_di.dt` 的 `field_role` 仍为 NULL 原值） |
| `/api/health/drift` | 恢复 `consistent=true`、`columnDrift={}` |
| `metric_def` | 13 行，无差异 |
| `gate_config` | 5 行，`max_complexity=3`（Ticket 15 探针写入，已还原 `updated_by`）；本票只做了两次**被 400 挡回**的非法写入，未落库 |
| `mall_dw` | 只读，未写 |
| `agent_run` / `agent_step` | 2269→2467 / 19958→21627（含本票探针，append-only） |

## 6. 修复建议

1. **把 C5② 的「显式拒绝」落到生成器**：在 `RewriteStep`/IR 生成侧加一条「口径不支持」的识别（`累计支付用户`、`累计支付人数`、`近 N 天支付用户数`），拒绝文案带上口径原因（「支付用户数是当日去重人数，跨天相加会重复计人；请按天查看，或改用订单数」），并在 `stub_ir` 加一条对应样例与一个单测。否则 §6.5 那句「用户问到时怎么答」只对读文档的人有效。
2. 顺手修掉 §6.5 结尾「代码不用动」的表述 —— 口径注释不用改代码，但「显式拒绝」需要代码配合，两件事分开写。
3. `check_docs_consistency.py` 的编号检查在注释里补一句「中文数字不纳入，原因是散文里存在『闸门三个页面』这类量词」，避免后人重复踩。
4. `SchemaDrift` 建议加一个「引擎声明的表」过滤（只比 `EngineWhitelist.effective()` 里的表），把「库里多出来的表」降级成 info 而不是让 `consistent=false`。
