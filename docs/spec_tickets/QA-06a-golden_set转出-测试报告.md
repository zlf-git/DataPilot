# QA-06a golden_set 转出 —— 独立测试报告（附 05c 补丁复验）

> 角色：独立 QA（只读验收）。不采信交付说明：`expect_rows` 用 mysql CLI 独立重跑、变异用例自造、文档真源性用副本反向验证。
> 元数据写操作只在隔离库 `qa_dp06` 上做（测完 DROP）；业务库 `mall_dw` 全程只读。

---

## 1. 元信息

| 项 | 值 |
|---|---|
| 被测票号 | Ticket 06a（附 05c 补丁） |
| 被测版本 | 06a = `ec2d1a9`；05c = `ceade40` |
| 测试期间新增提交 | `fcf3665`（QA-05 验收补记，只改 docs）、`b830259`（Ticket 06b，新增 `eval/run_eval.py` + `result.json`） |
| 关键基线确认 | `eval/golden_set.json`、`gen_golden_set.py`、`check_golden_set.py` 自 `ec2d1a9` 起**未被改动**（mtime 13:06/13:07/13:08，`git diff ec2d1a9..HEAD -- eval/` 只多出 06b 两个文件）；`src/` 自 `ceade40` 起无变化 → 本报告结论对 HEAD `b830259` 同样成立 |
| 测试时间 | 2026-10-21 13:20 — 14:05 GMT+8 |
| 运行方式 | 数据层：`mysql` CLI 独立执行 + 自写 Python 对拍；服务层：`git archive ceade40` → `/tmp/dp06` 编译，端口 18090/18091/18092 |
| 元数据库 | 隔离库 `qa_dp06`（ceade40 版 schema + seed，13/28/4/12 行 9 唯一/16），测完 DROP |
| 业务库 | StarRocks `mall_dw` 3.3.8-e3816ec，只读 |
| 结论 | **PASS（有条件）** —— 票面 4 任务 + 4 验收全部达成；校验器非恒 PASS（22 例变异全按预期）；5 条 P2 都与「校验覆盖度」有关，无 P0/P1 |

---

## 2. DoD 逐条判定

### 2.1 票面任务

| # | 任务 | 判定 | 证据 |
|---|---|---|---|
| T1 | 提取 15 题 7 个字段 | PASS | 实测字段集合 = `no/difficulty/question/expect_type/expect_sql/expect_cols/expect_rows/expect_value/row_order_stable/sql_must/window`，票面要求的 6 个字段全在（`difficulty` 额外） |
| T2 | `expect_type` 只允许三值、`expect_value` 形状匹配 | PASS | 分布 scalar=5 / stringset=2 / rowset=8；scalar 全是数字、stringset 全是 `list[str]`、rowset 全是 `list[dict]`（逐题打印验证）；根级 `type_rules` 与 `judge_rules` 写清了判定口径 |
| T3 | 15 条 `expect_sql` 单独执行、`expect_rows` 为实跑值 | PASS | 用 **mysql CLI（非项目连接）** 逐条独立执行：15/15 成功、列名全对、行集合全对；11 个数值标量与 StarRocks 手算逐位一致（Q15 `0.3832/0.3226/0.2942`、Q6 `0.1/0.1/0.075`、Q9 `0.9`） |
| T4 | 生成 `eval/golden_set.json` | PASS | 文件 sha256 `6cfa7d9ee1c0b70a…`，与 06b 票面登记值一致；`sources` 记了两份来源哈希；`target` 记了版本、锚点、四表行数 7/21/35/21 |

### 2.2 票面验收标准

| # | 验收点 | 判定 | 证据 |
|---|---|---|---|
| A1 | 15 题字段齐全 | PASS | 15 题全部含 7 个必需字段 + 4 个扩展字段 |
| A2 | `expect_type ∈ {scalar,stringset,rowset}` 且形状匹配 | PASS | 见 T2；变异「Q4 改 scalar」被结构校验拦（3 行 1 列 ≠ 1 行 1 列） |
| A3 | 15 条 `expect_sql` 逐条执行成功 | PASS | 独立 CLI 执行 15/15 |
| A4 | `expect_rows` 与执行结果一致 | PASS | 独立执行 15/15 对拍一致；`check --replay` 亦 PASS（15/15 重放一致） |

### 2.3 生成器与校验器的行为验证（超出票面，但决定交付是否可信）

| 用例 | 期望 | 实测 |
|---|---|---|
| `gen --dry-run` 可重复生成 | 不改盘、结论稳定 | exit=0，`git status eval/` 空 |
| 文档 SQL 块 == golden_set.expect_sql | 完全一致（证明是解析不是手抄） | **15/15 字符串完全一致** |
| 改副本文档 Q7 日期（值会变） | 拒绝生成 | exit=1，「Q7 实测与期望表不符：缺期望行 上海 15695.05，实测 北京 16147.89」 |
| 改副本文档 Q3 列别名（值不变） | 允许（只比值） | exit=0（设计如此，见 P2-4 观察） |
| 删副本文档 Q7 整块 | 拒绝生成 | exit=1，「缺题：[7]」 |
| 复制 Q7 成 Q8 | 拒绝生成 | exit=1，「Q8 重复出现，解析有歧义」 |
| `VERSION()` vs `current_version()` | 5.1.0 是协议号 | 实测 `VERSION()=5.1.0`、`current_version()=3.3.8-e3816ec`，交付说法正确 |

---

## 3. 用例明细

### 3.1 独立执行对拍（mysql CLI，不 import 项目脚本）

| 题 | 类型 | 实测 | 与落盘 |
|---|---|---|---|
| Q1 | scalar | `53042.41` | OK |
| Q2 | scalar | `69` | OK |
| Q3 | scalar | `15604.97` | OK |
| Q4 | stringset | 上海 / 江苏 / 浙江 | OK |
| Q5 | stringset | 4K显示器 / 机械键盘 | OK |
| Q6 | rowset | APP 0.1 / 小程序 0.1 / 网页 0.075 | OK |
| Q7 | rowset | 上海 15695.05 | OK |
| Q8 | rowset | 4K显示器 4 / 机械键盘 3 / 人体工学椅 3 | OK（集合） |
| Q9 | scalar | `0.9` | OK |
| Q10 | scalar | `2` | OK |
| Q11 | rowset | APP 138821.73 | OK |
| Q12 | rowset | 华东 254466.85 | OK |
| Q13 | rowset | 电脑外设 293469.91 | OK |
| Q14 | rowset | 2026-10-16 66184.50 | OK |
| Q15 | rowset | APP 0.3832 / 网页 0.3226 / 小程序 0.2942 | OK |

### 3.2 变异测试（22 例，全部按预期）

| 变异 | 结构校验 | --replay | 结论 |
|---|---|---|---|
| 基线 | 0 | 0 | 参照 |
| Q1 值 +0.01（容差内） | 0 | 0 | 容差 0.011 生效 |
| Q1 值 +0.02（超容差） | **0** | 1 | 只有 replay 能抓 |
| Q1 值改字符串 `"53042.41"` | 0 | — | 允许（标量可为字符串） |
| Q4 类型改 scalar | 1 | — | 行/列数不符 |
| **Q1 类型改 rowset** | **0** | — | **漏判**（P2-3） |
| 类型改大写 `SCALAR` | 1 | — | 值域校验生效 |
| 删 Q15 | 1 | — | 题数校验 |
| 删 `tolerance` | 1 | — | 顶层字段校验 |
| Q8 expect_cols 改错（与 SQL 不符） | **0** | 1 | 只有 replay 能抓 |
| Q8 少一行 | **0** | 1 | 只有 replay 能抓 |
| Q8 重复一行 | **0** | 1 | 只有 replay 能抓 |
| Q1 SQL 改错日期 | **0** | 1 | 只有 replay 能抓 |
| Q4 stringset 乱序 | 0 | — | 顺序无关（正确） |
| Q8 `expect_value` 乱序 | 1 | — | value 必须严格等于 rows（内部一致性） |
| 窗口题删 `DT > ?` | 1 | — | QA-04 硬规则生效 |
| **窗口题删整个 `window`** | **0** | — | **漏判**（P2-4） |

结论：校验器**不是恒返回 PASS**（用户交付的 5 例变异成立），但存在四类「只有 --replay 才抓」的漂移和两处结构漏判。

### 3.3 Q8 行序：30 次采样，两种顺序各 15 次

| 采样方式 | 结果 |
|---|---|
| 每次新连接 × 30 | `4K显示器 / 人体工学椅 / 机械键盘` **15 次**；`4K显示器 / 机械键盘 / 人体工学椅` **15 次** |
| Q4 `DISTINCT` × 10 | 10/10 一致 |
| Q6 并列排序 × 10 | 10/10 一致（tie-breaker 生效） |
| `gen --dry-run` 三次 | 两次「行序两轮一致 15/15」，一次「不一致的题：[8]」 |

**结论**：Q8 并列行序是 50/50 的随机翻转，交付说的「两次采样一次翻转」不是偶然，而是必然复现。`row_order_stable` 这个字段在 Q8 上落盘为 `true`，只是抽样那一次恰好一致。

### 3.4 06b 约束可行性（顺手验证，不评判 06b 实现）

| 约束 | 验证 |
|---|---|
| rowset 必须集合比对 | Q8 50/50 翻转，按位置比必误判 —— 证据成立 |
| `expect_cols` 只比列数、不比列名 | 15 题里 **10 题**列名与生成器输出不同（Q1 `gmv_total` vs `total_gmv`、Q2 `paid_order_cnt` vs `total_paid`、Q15 `gmv_channel_ratio` vs `ratio`…），列数 **15/15 一致** → 约束 2 是必需的且可行 |
| 窗口题必须断言 SQL 形状 | golden_set 里 `window` 恰 5 条（Q2/Q11~Q14），`sql_must` 15/15 齐全 |
| `probe_window3` 单列回归 | 不在 15 题内（已确认），`src/main/resources/stub_ir/probe_window3.json` 存在 |

---

## 4. 缺陷清单

### P0 / P1
无。数据层零错值：15 条独立执行全对，与文档、期望表三方一致。

### P2

**P2-1 `sources.sha256` 是只写不校验的死指纹，且当前已过期**
位置：`eval/golden_set.json:7-10`、`eval/check_golden_set.py`（全文无 sha256 比对）
实测：golden_set 记 `docs/评测集.md (sha256:39f17f4897a0)`，当前文件实际 `6abbf84e0592` —— `fcf3665` 在 13:26 追加了跨表对账 2 行（SQL 块未动，故本次对拍仍 15/15 一致）。校验器不重算、不比对，所以「文档改了、机读真源没重生成」完全静默。
影响：06b 的 `result.json` 会带 `golden_set.sha256`，但没人能证明这份 golden_set 对应当前文档。若哪天文档 SQL 块被改，golden_set 会静默过期，评测结论失效却看不出来。
建议：`check_golden_set.py` 增加「重算两份来源 sha256 与 `sources` 比对」，不一致 exit=1（或 `--allow-stale` 降级为 warn）。

**P2-2 不带 `--replay` 时，值/列/行/SQL 四类漂移全部放行**
位置：`eval/check_golden_set.py:221`（`if args.replay:`）
实测：超容差值、列名不符、少一行、重复行、SQL 改错日期 —— 五种变异在结构模式下全部 exit=0，只有 `--replay` 才 exit=1。
影响：如果 CI 或 06b 前置检查只跑 `python eval/check_golden_set.py`，等于只验了「文件自己跟自己对得上」。用户交付的 5 例变异能全 exit=1，是因为都在 replay 模式下跑的。
建议：把 `--replay` 改为默认，加 `--no-replay` 作为离线逃生口；或至少在 docstring 与票面把 `--replay` 写成强制。

**P2-3 `rowset` 分支不校验形状，类型标错也能过结构校验**
位置：`eval/check_golden_set.py:87-92`
实测：把 Q1 从 `scalar` 改成 `rowset`、`expect_value` 改成对象数组 → exit=0（rowset 分支只要求「value 是对象数组且等于 rows」）。
影响：`gen` 侧有 `DECLARED_TYPE` 交叉校验兜底，但那是生成器；如果 golden_set 被手工改动或未来从别处导入，check 抓不到「1 行 1 列却标成 rowset」这种口径错误。
建议：rowset 分支加一条「不得是 1 行 1 列（那是 scalar 形状）」，与 `type_rules` 对齐。

**P2-4 窗口题的 `window` 字段被删不会被发现**
位置：`eval/check_golden_set.py:211-219`（只在 `win` 存在时才校验）
实测：删掉 Q2 的 `window` → exit=0。
影响：06b 靠 `window` 决定哪些题要断言 SQL 形状。删掉一条，06b 会静默跳过该题的窗口断言，窗口口径回归就从「5 题必查」变成「4 题必查」而无人知道。
建议：check 断言「`window` 非空的题号集合 == {2,11,12,13,14}」，少一条就失败。

**P2-5 `row_order_stable` 在 Q8 上是事实错误**
位置：`eval/golden_set.json` Q8 `row_order_stable: true`；`gen_golden_set.py:231-245`（两轮采样写死）
实测：30 次独立采样两种顺序各 15 次；三次 `--dry-run` 里两次 true 一次 false。字段值完全取决于生成那一刻的运气。
影响：`judge_rules` 写了「仅作证据、不作判据」，但字段名 `row_order_stable=true` 会让不看 judge_rules 的人（尤其 06b 之后的维护者）以为 Q8 稳定，从而在别处按位置比。
建议：改名 `order_stable_2rounds_sample` 并附采样时间；或直接删掉（判定口径已由 `judge_rules.rows` 固定为集合语义）。

### 观察项（不算缺陷，但会影响后续判断）

1. **`expect_value` 对 rowset 是 `expect_rows` 的严格拷贝且要求同序**（check 第 91 行用 `json.dumps` 整体比较）。变异「Q8 value 乱序」exit=1 是这个原因，不是判定口径要求顺序。字段没有独立信息量，建议删掉或改成集合比较，避免「value 必须同序」的误解。
2. **`gen` 只看值不看 SQL 文本**：改文档里的别名/列序仍 exit=0。这是有意的（对拍只比值），但它意味着「文档标准 SQL 写错列序」不会被告警 —— 与 06b 只比列数的决定叠加后风险可控。
3. **06b 已落地**（`b830259`）：`result.json` 里 `golden_set.sha256=6cfa7d9e…` 与当前文件一致、`code.dirty_files=2`（run_eval.py + result.json 自身）、15 题全 PASS、集合比对与 `probe_window3` 都按 06a 约束实现。06b 本身的验收不在本报告范围。

---

## 5. 数据清理

| 项 | 状态 |
|---|---|
| 隔离库 `qa_dp06` | 已 DROP（含 6 条 `zz_*`/临时指标改动、`max_join` 被临时置 0 后已还原 1） |
| `data_agent` 活库 | 未写：指标 13（测试前后核对一致），字段 28 / 闸门 4 未变（服务 health 与库内双向核对） |
| 临时桩 | `target/classes/stub_ir/e_*.json` 3 个已删；`src/main/resources/stub_ir` 全程未写，仍 17 个（15 题 + `neg_cross_table` + `probe_window3`） |
| 端口 | 18090/18091/18092 已释放；18080 复核确已释放（上一轮报告写的 PID 1880 占用已解除） |
| 文档副本 | `/tmp/dp06/docs/评测集.md` 4 次变异后已按备份还原，字节级一致 |
| 工作区 | `git status` 干净；测试改动全部在 `/tmp/dp06` 与 `/tmp/*.py` |
| 未清理 | `/tmp/dp05`、`/tmp/dp06` 固定副本保留以便复现（可随时删） |

---

## 6. 修复建议（按优先级）

1. **P2-2 先修**（一行改动）：`--replay` 设为默认。这是「校验器到底验了什么」的分水岭，不修则六成变异都是敞开的。
2. **P2-1 一起修**：check 里加来源 sha256 比对。当前值已经过期，正好可以验证修复后的告警是否真的触发。
3. **P2-4 + P2-3**：补「窗口题号集合固定」与「rowset 不得 1 行 1 列」两条断言，堵住结构性漏判。
4. **P2-5**：改字段名或删字段。Q8 的 50/50 翻转已经足够证明「这个字段不可作为判据」，留着只会误导。
5. **观察项 1**：`expect_value` 对 rowset 的严格同序比较建议改成集合，避免后续维护者以为「value 顺序有意义」。

---

## 7. 附：05c 补丁复验（`ceade40`）

| 条目 | 独立实测 | 判定 |
|---|---|---|
| P2-1 空结果提示被吞 | 0 行结果（`gmv_channel_ratio` + `dt=2026-01-01`）→ `answer='无数据：查询结果为空，请检查筛选条件'`，`gates` 末位 `结果自检=PASS(WARN)` | 修复生效 |
| P2-1 延伸 单行全 NULL | `gmv_total` + 无数据日期 → `rows=[{gmv_total: null}]`、`answer='无数据：查询结果为空（聚合返回空值），请检查筛选条件'` | 修复生效（此前静默空串） |
| P2-2 ratio 归因 | 旧 ratio 桩 → `answer='占比语义请改用复合指标（Ticket 05）：IR 的 ratio{numerator=gmv_channel, denominator=gmv_total} 不再直接消费…'`，不再报「指标不存在」 | 修复生效 |
| P2-4 代价闸门漏分母表 | 直接改库 `max_join=0` + 重启 → Q15 拒「join 表数 1 > 0」；Q1 单表 PASS；**Q6 同表占比 PASS**（同表分母未被误算成 join）；Q11 单表 PASS；还原 1 后 Q15 恢复 3 行 | 修复生效 |
| P2-6 seed 不收敛 | 把 `gmv_channel_ratio` 的 `metric_type/metric_sql/denominator_*` 四字段改坏 → 重跑 seed → 四字段全部拉回基线；再跑一次指标仍 13 条 | 修复生效 |
| P2-7 迁移硬编码库名 | 连接不选库执行：V05 在第 3 条 `PREPARE stmt FROM @sql` 报 `1046 No database selected`；V05b 在首条 `INSERT` 报 `1046`；带库名执行 V05 11 条 / V05b 4 条全成功 | 修复生效（不会误写 `data_agent`） |
| P2-3 契约文档化 | `docs/执行模块.md §3.3` 写明「写入口禁外括号、`braces()` 只兜历史数据」 | 已收口（不改代码的选择合理） |
| 回归 | `qa/e2e_stub.py` PASS：可用 15/15、答对 15/15、静默错 0、引擎缺陷 0、回归 0、反例不符 0、行序不稳 0、**窗口探针不符 0**（`157702.85`） | 零回归 |

**05c 的两点观察（不判缺陷）**

1. `PUT /api/gate-config` 必须传 `gateName`，但服务端随后按 `paramKey` 归一化覆盖它 —— 客户端被迫传一个会被丢弃的字段，不传则 400「gateName 不能为空」。这是 01b 遗留契约，建议 `gateName` 改为可选（由 `paramKey` 推导）。
2. 05c 把「单行全 NULL」统一判为无数据。对聚合空集是对的；若将来出现「某维度下全 NULL 属于合法结果」的场景（如外连接占位），文案会略偏。当前数据形态碰不到。

---

## 8. 自我更正（上一轮结论）

上一轮 QA-05 报告提到「04 报告里 185000 vs 112000 的口径分歧」—— 经 `grep` 全仓核实，**仓库 `docs/` 内不存在这两个数字**，该说法来源是我把更早对话里的旧记忆错挂到了 QA-04 报告上。本轮实测：`2026-10-13 ~ 10-19` 七天，概览表 `total_gmv`、渠道表 `gmv`、地域表 `gmv` 逐日完全相等（锚点日同为 `53042.41`），跨表占比分母没有口径分歧。旧结论作废，以 QA-05 报告 §8.3 与本报告为准。
