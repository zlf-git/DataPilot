# QA-03 IR 结构与生成器完整实现 — 测试报告

> 独立 QA 测试。只验证外部行为，不改被测代码、不改库内数据。测试数据前后快照与还原见第 5 节。

## 1. 元信息

| 项 | 内容 |
|---|---|
| 被测票号 | Ticket 03（IR 结构与生成器完整实现） |
| 被测版本 | `9e1f4be`（Ticket 03 落地提交）；另在 `HEAD=6cb6e1c`（Ticket 04）上做回归确认 |
| 测试时间 | 2026-10-21 12:05 — 12:30 |
| 运行方式 | `java -cp target/classes:<deps> com.zlf.datapilot.DataPilotApplication --server.port=18080`（`provider=stub`）；固定副本在 18088 |
| JDK | JBR 21.0.7（`JAVA_HOME=/d/IntelliJ IDEA 2025.1.3/jbr`） |
| 依赖环境 | MySQL 元数据 `mysql-ai-analysis`@3307 healthy；StarRocks `sr-ai-analysis`@9030 healthy；基线 metric=11 / field=28 / gate=4 / dim=9 / fewshot=1 |
| 语料 | `src/main/resources/stub_ir/q1~q15.json`（15 条），另注入 29 条临时桩做边界测试（只落 `target/classes`，已删除） |
| 一句话结论 | **票面能力全部落地，15 题 13 答对 + 2 显式拒绝 + 0 静默错，与交付声明一致；但「few-shot 污染」只修了一半——空白问题仍会返回 Q1 的 53042.41，属新引入的静默错。** |

### 1.1 测试期间的环境事故（必须记录）

Ticket 04 的开发在本次测试进行中落盘并提交：`TimeRange.java` 创建于 12:08:14，`QueryIr.java` 改于 12:08:26，`q12.json` 改于 12:09:15，`target/classes` 于 12:12:54 被重新编译，最终提交 `6cb6e1c`。

- 12:07 / 12:08 两轮测试跑的是 **9e1f4be 的编译产物**（可核验：这两轮 Q12 的 SQL 为 `... FROM ads_region_gmv_di GROUP BY region ...`，无 `dt >`）。
- 12:22 那轮重启时 `target/classes` 已被 Ticket 04 覆盖（Q12/Q14 变成 `WHERE dt > ? AND dt <= ?`）。
- 为保证结论可复现，**另用 `git archive 9e1f4be` 导出固定副本单独编译复测**（18088 端口），结果与 12:07/12:08 两轮完全一致。本报告结论均以固定副本为准。
- 附带的坑：从 `HEAD` 读 `qa/eval_expectations.json` 会拿到 Ticket 04 版本的 `sqlMust`（含 `dt > ?` / `dt <= ?`），用它评 03 版会误报 5 条静默错。**期望表与代码必须同版本比对**，06b 建议在脚本里打印期望表来源 commit。

---

## 2. DoD 逐条判定

### 2.1 任务清单（9 项）

| # | 任务 | 结果 | 证据 |
|---|---|---|---|
| 1 | QueryIr 加 `selectMode{AGG,DETAIL}`，DETAIL 时 dimensions≥1 | ✅ | 枚举与字段就位；DETAIL 无维度 → `DETAIL 明细模式必须指定至少一个维度`；未声明 selectMode 时按 metrics 推断（A1→AGG、A2→DETAIL 均正确） |
| 2 | DETAIL 生成 `SELECT DISTINCT` | ✅ | Q4 `SELECT DISTINCT province_name FROM ads_region_gmv_di WHERE region = ?`；Q5 同理 |
| 3 | DETAIL 选表反查（dimension_def field→table） | ✅ | 单表命中（Q4/Q5）；歧义 `维度 [dt] 对应多张候选表 [4 张]`；无共同表 `维度 [province_name, product_name] 在维度字典中没有共同所属的表`；双维度同表 `category_name+product_name → ads_product_sales_rank_di` 正确 |
| 4 | OrderBy 支持 metricId 别名 | ✅ | Q7 `ORDER BY gmv_region DESC`、Q11~Q13 同；按字段名排序也支持（`ORDER BY province_name ASC`）；越界 `排序字段 gmv_channel 不在结果列 [gmv_total] 内` 显式报错 |
| 5 | limit 空时注入 `gate_config.max_rows` | ✅ | 读的是活库，不是硬编码：PUT `max_rows=7` 后 Q2 立刻变成 `LIMIT 7`，改回 1000 又变 `LIMIT 1000`；`limit=0` / `-5` 回落 1000；`limit=5000` 被代价闸门拦（`limit 5000 超过上限 1000`） |
| 6 | FilterOp 删除 BETWEEN | ✅ | 枚举仅 `EQ/IN/LIKE/GT/GE/LT/LE` 七个值，全仓无 BETWEEN |
| 7 | 参数分派 EQ/IN/LIKE/GT/GE/LT/LE | ✅ | IN 数组 → `IN (?, ?)` + 3 参数；IN 逗号串 → 同样 2 占位符；LIKE → `%显示器%`；GT/GE/LT/LE 各 1 参数且操作符正确；IN 空数组 → `IN 过滤值不能为空` |
| 8 | Prompt 注入 today + 日期归一化规则 + few-shot 1 条 | ⚠️ 代码核对通过，行为不可验 | `PromptBuildStep` 已注入「今天是 %s（年份 %d）」+ 3 条规则 + selectMode/ratio/orderBy 说明；DB `few_shot_example` 有 1 条。但桩不消费 prompt，**日期归一化在 stub 模式下无法做行为验证**，只能等真 LLM 联调 |
| 9 | 更新 q4/q5/q6/q11~q13 stub | ✅ | 15 条全部可反序列化；q4/q5/q8 补 DETAIL、q14 改 AGG、q15 补 ratio，其余保持 |

### 2.2 验收标准（6 条）

| 验收标准 | 结果 | 实测 |
|---|---|---|
| Q4/Q5 生成 SELECT DISTINCT | ✅ | 见上 |
| Q11~Q13 按聚合别名排序 | ✅ | 三题 SQL 均 `ORDER BY <metricId> DESC LIMIT 1`，值 138821.73 / 254466.85 / 293469.91 全对 |
| 无 limit 的 IR 生成带 LIMIT | ✅ | 15 题 SQL **全部**带 LIMIT（含单行聚合） |
| 三种 op 参数个数正确 | ✅ | 实测 6 种 op，参数个数与占位符数量一一对应 |
| 「10月19日」归一化为 `dt='2026-10-19'` | ⚠️ 不可行为验证 | 见任务 8 |
| DETAIL 无指标时维度字典反查表成功 | ✅ | 见任务 3 |

### 2.3 交付声明的关键指标复核

| 声明 | 独立复核 | 结论 |
|---|---|---|
| IR 命中 15/15 | 15/15 | ✅ 一致（但如 01c 所述，只证明桩匹配） |
| 链路可用 13/15 | 13 PASS + 2 显式拒绝 = **15/15 有明确归属** | ✅ 一致（"13/15"＝期望能答的 13 题全答对） |
| SQL 形状达标 13/13 | 13/13 通过 `sqlMust` 断言 | ✅ |
| 答对（行级对拍）13/13 | 13/13 与 StarRocks 手算值一致 | ✅ |
| 静默错 0 | **本报告判 1** | ❌ 见 P1-1（15 题语料内确实为 0，空白输入不在语料内） |
| 引擎缺陷 0 / 回归 0 | 15 题内 0；空白输入 1 处回归 | ⚠️ 同上 |
| 显式拒绝 2（Q8/Q15 归 Ticket 05） | 归因正确、文案含 blocker | ✅ |

---

## 3. 用例明细

### 3.1 15 题端到端（固定副本 9e1f4be，服务 18088）

| # | 判定 | 生成的 SQL（节选） | 结果 |
|---|---|---|---|
| 1 | PASS | `SELECT SUM(total_gmv) AS gmv_total FROM ads_trade_overview_di WHERE dt = ? LIMIT 1000` | 53042.41 |
| 2 | PASS | `SELECT SUM(paid_order_cnt) AS paid_order_cnt FROM ads_trade_overview_di LIMIT 1000` | 69 |
| 3 | PASS | `... ads_channel_conversion_di WHERE dt = ? AND channel_name = ?` | 15604.97 |
| 4 | PASS | `SELECT DISTINCT province_name FROM ads_region_gmv_di WHERE region = ?` | 上海/江苏/浙江 |
| 5 | PASS | `SELECT DISTINCT product_name FROM ads_product_sales_rank_di WHERE category_name = ?` | 4K显示器/机械键盘 |
| 6 | PASS | `SELECT channel_name, SUM(paid_order_cnt)/SUM(order_cnt) AS pay_conversion_rate ... GROUP BY channel_name ORDER BY pay_conversion_rate DESC` | APP 1.0 / 小程序 1.0 / 网页 0.75（**每组各自算比值**，不是全局 0.9 复制 3 行） |
| 7 | PASS | `... GROUP BY province_name ORDER BY gmv_region DESC LIMIT 1` | 上海 15695.05 |
| 8 | 显式拒绝 | `sql=null` | 排序字段 sale_cnt 不在结果列 [product_name] 内（Ticket 05） |
| 9 | PASS | `SELECT SUM(paid_order_cnt)/SUM(total_order_cnt) AS pay_rate ... WHERE dt = ?` | 0.9 |
| 10 | PASS | `... ads_region_gmv_di WHERE dt = ? AND region = ?` | 2 |
| 11 | PASS | `... GROUP BY channel_name ORDER BY gmv_channel DESC LIMIT 1` | APP 138821.73 |
| 12 | PASS | `... GROUP BY region ORDER BY gmv_region DESC LIMIT 1` | 华东 254466.85 |
| 13 | PASS | `... GROUP BY category_name ORDER BY sale_amount_product DESC LIMIT 1` | 电脑外设 293469.91 |
| 14 | PASS | `... GROUP BY dt ORDER BY gmv_total DESC LIMIT 1` | 2026-10-16 66184.50 |
| 15 | 显式拒绝 | `sql=null` | 占比计算未实现（Ticket 05）：分子=gmv_channel 分母=gmv_total |

**标准值全部独立手算**（`docker exec sr-ai-analysis mysql -P9030 ...`），与 `qa/eval_expectations.json` 逐题一致：7 天 `total_gmv` 合计 390001.84、`paid_order_cnt` 合计 69、渠道 7 天 APP 138821.73、大区 华东 254466.85、类目 电脑外设 293469.91、单日最高 2026-10-16 = 66184.50。**13 题的值没有一个错。**

### 3.2 边界与对抗（29 条临时桩，全部按预期）

| 组 | 用例 | 结果 |
|---|---|---|
| A selectMode 推断 | 不声明 + 有指标 → AGG；不声明 + 无指标有维度 → DETAIL | 2/2 正确 |
| B DETAIL 反查 | 歧义(dt)、无共同表(province_name+product_name)、未入字典(ghost_dim)、DETAIL 带指标、AGG 无指标、多指标 | 6/6 显式拒绝，文案精确（含候选表清单） |
| C 参数分派 | IN 数组 / IN 逗号串 / LIKE / GT / GE / LT / LE / IN 空数组 | 8/8，占位符与参数一一对应 |
| D limit 兜底 | limit=0、limit=-5、limit=5000（超上限） | 前两个回落 1000，第三个被代价闸门拦 |
| E 排序契约 | AGG 按维度字段排序、AGG 按他指标别名排序、DETAIL 按维度字段排序 | 允许/拒绝/允许，均符合契约 |
| F 列位注入 | 维度 `dt = ? UNION SELECT 1`、过滤 `dt; DROP TABLE x` | 2/2 在存在性闸门被拒 |
| G 表作用域 | AGG 指标 gmv_total + 维度 channel_name（跨表） | **闸门全 PASS，DB 报错 → 见 P1-2** |
| H ratio | 幻觉分子、合法声明 | 前者存在性闸门拒（点名 ghost_metric），后者 Ticket 05 显式拒 |
| 附加 | DETAIL 双维度同表（category_name+product_name） | 正确反查到 ads_product_sales_rank_di，`SELECT DISTINCT` 两列 |

### 3.3 Few-shot 污染修复验证

| 输入 | 期望 | 实测 |
|---|---|---|
| `10月19日当天的总GMV是多少？` | 命中 q1 | ✅ 53042.41 |
| `今天天气怎么样` | 未命中 | ✅ `无法识别查询意图，请换个问法` |
| `10月19号当天的总GMV是多少` 等 5 种格式扰动 | 归一化后命中 | ✅ 5/5 |
| **`""` / `" "` / `"\n"`** | 应无法识别 | ❌ **全部返回 53042.41，answer 为空** |

**根因**：`StubLlmClient.extractQuestion` 在抽到的「问题」为空时 `return prompt`（回落到整段 prompt），而 prompt 里的 few-shot 正文正是 Q1 原题 → 又命中 q1。**被修的坑换了个入口重新出现。**

### 3.4 交付自带验收脚本复核

| 脚本 | 结果 |
|---|---|
| `qa/verify_stub_ir.py` | 15/15 通过，白名单取自运行时（实引用 8 指标 / 6 字段全部命中）；服务未起时 `EXIT=2` 拒绝假绿 ✅（01c P1-3 已闭环） |
| `qa/e2e_stub.py` | `CONCLUSION: PASS（可用 13/15，答对 13，静默错 0，显式拒绝 2）`；格式扰动 5/5；未命中兜底 OK ✅ |

---

## 4. 缺陷清单

### P1-1 空白问题返回 Q1 的数据（静默错，Ticket 03 引入）

| 项 | 内容 |
|---|---|
| 复现 | `POST /api/ask {"question":""}`（或 `" "`、`"\n"`） |
| 实际 | `sql=SELECT SUM(total_gmv) ... WHERE dt=? LIMIT 1000`，`rows=[{"gmv_total":53042.41}]`，`answer=""` |
| 期望 | `answer="无法识别查询意图，请换个问法"`、`sql=null`（Ticket 03 之前的行为） |
| 位置 | `llm/StubLlmClient.extractQuestion`（`q.isBlank()` 时 `return prompt`）+ `agent/step/PromptBuildStep.buildUserPrompt`（few-shot 正文含 Q1 原题） |
| 影响 | 空入参拿到一条「看起来完全正常」的错误数据，无任何提示；若前端把 answer 为空当成功渲染，就是直接给用户错数 |
| 建议 | ① `extractQuestion` 抽到空白就返回 `""`（空串匹配不到任何非空 stub），**不要回落整段 prompt**；② `AgentEngine.run` 入口对 `question` 为 blank 直接短路成「无法识别」或 400，别让它走到 LLM |

### P1-2 AGG 的 GROUP BY 维度不校验表归属，跨表维度穿到 DB 才报错（且在 answer 里泄漏内部异常）

| 项 | 内容 |
|---|---|
| 复现 | IR：`metrics=[gmv_total]`（表 ads_trade_overview_di）+ `dimensions=[channel_name]`（属 ads_channel_conversion_di） |
| 实际 | `SELECT channel_name, SUM(total_gmv) AS gmv_total FROM ads_trade_overview_di GROUP BY channel_name LIMIT 1000`，五闸门 `存在性/权限/代价/语法` 全 PASS，最后 DB 报错；`answer` 原文为 `SQL 执行失败：BadSqlGrammarException: PreparedStatementCallback; bad SQL grammar [...]` |
| 期望 | 生成阶段或语法闸门就拒绝，文案为「字段 channel_name 不属于表 ads_trade_overview_di」 |
| 位置 | `sql/IrToSqlConverter.buildAgg`（维度只过 `requireField` 格式校验，不查 table.field）；`sql/SqlValidator.extractColumns`（比的是全局字段名） |
| 备注 | 这与 QA-02 报告 P1-2 是**同一条**：`OntologyCache.tableFields()`（`table.field` 复合键集合）已建好，全仓仍无人调用。Ticket 03 让维度也能任意填字段，命中面从"指标表选错"扩大到"维度/过滤字段跨表"，严重度上升 |
| 建议 | converter 在 AGG 里校验 `tableName + "." + 维度/过滤字段 ∈ cache.tableFields()`（一次 O(1) 查找）；`SqlExecuteStep` 把 DB 异常收敛成用户可读文案，不要透 `BadSqlGrammarException`/SQL 原文 |

### P2（7 条）

| # | 问题 | 位置 |
|---|---|---|
| 1 | 双意向拼接静默只取一个：`近7天GMV最高的渠道是哪个？另外10月19日总GMV是多少` → 只回 Q11，无任何提示（01c P2-4 未变，few-shot 让 prompt 更长后更易触发） | `StubLlmClient.generate` 部分匹配分支只打 debug 日志 |
| 2 | Q4/Q5 是 DETAIL 且无 orderBy，行序不确定（两轮实测 `上海/浙江/江苏` 与 `上海/江苏/浙江` 不同）。06b 若对 DETAIL 结果做顺序比对会随机失败 | `qa/eval_expectations.json`（当前只声明集合比对，需在 06b 明确写死） |
| 3 | Q6 `ORDER BY pay_conversion_rate DESC` 存在并列（APP 与 小程序 均为 1.0），单键排序下行序不稳定，"从高到低" 的判定需要 tie-breaker 或改集合比对 | stub q6 / 06b 判定 |
| 4 | 维度字典覆盖不足：`dimension_def` 仅 9 行 / 6 个唯一字段，字段白名单有 28 个。DETAIL 只要落到其余 22 个字段上就是「均不在维度字典」；`dt` 在 4 张表 → 任何以 dt 为维度的 DETAIL 恒判歧义 | `deploy/seed.sql`（Ticket 16 收口） |
| 5 | `max_metric`(3) 与 `max_join`(2) 阈值在当前生成器下不可达：多指标在转换器就被「Ticket 05 未实现」拒、生成器只支持单表。2 指标时代价闸门仍 PASS | `agent/gate/CostGate` + `IrToSqlConverter` |
| 6 | `IR_Schema.md` §2 的 `QueryIr` record 定义仍是旧结构（无 selectMode/ratio），与文档末尾的契约表自相矛盾；新字段只出现在表里 | `docs/IR_Schema.md` |
| 7 | 字段名正则 `^[a-z_][a-z0-9_]*$` 大小写敏感，大写字段名一律判非法（当前 seed 全小写，属潜在坑）；`IN` 值若为字符串则按逗号硬切，值本身含逗号会被拆错 | `IrToSqlConverter.FIELD_NAME` / `toList` |

---

## 5. 数据清理与还原

| 项 | 结果 |
|---|---|
| 临时桩 | 29 个 `zz*.json` 只写入 `target/classes/stub_ir`，**从未落 `src`**；测试后已 `rm`，`src/main/resources/stub_ir` 仍为 15 个 |
| `gate_config` | 测试前 mysqldump 快照（`/tmp/t03_gate_before.sql`）；`max_rows` 1000→7→1000，`updated_by` 已还原为 NULL。最终 `max_join=2 / max_limit=1000 / max_metric=3 / max_rows=1000`，与快照一致 |
| 元数据基线 | `metric=11 / field=28 / gate=4 / dim=9 / fewshot=1`，与测试前一致；未新增/删除任何指标、字段、维度 |
| 被测代码 | 未修改。仓库 `git status` 为空（HEAD `6cb6e1c`） |
| 临时副本 | `git archive 9e1f4be` 导出目录已删除；18080/18088 端口均已释放 |

---

## 6. 修复建议（按优先级）

1. **堵 P1-1**：`extractQuestion` 空白时不回落整段 prompt；Engine 入口对 blank 问题直接短路。这是本票唯一的新增静默错，改动 3 行以内。
2. **堵 P1-2**：`IrToSqlConverter` 用 `cache.tableFields()` 校验「指标表 + 维度/过滤字段」，同时把 `SqlExecuteStep` 的 DB 异常收敛成业务文案。这条从 02 拖到 03，越晚修泄漏面越大。
3. **06b 落判定口径**：DETAIL 结果一律集合比对（Q4/Q5），并列排序（Q6）加 tie-breaker 或改集合；把「期望表版本 = 被测 commit」写进评测脚本输出。
4. **Ticket 05 顺手补 dimension_def**：把 28 个字段补齐维度字典，否则 DETAIL 通道实际只有 6 个字段可用。
5. 文档：`IR_Schema.md` §2 的 record 定义补 selectMode/ratio，避免与契约表打架。

---

**测试脚本**（可复跑，改 `BASE`/`EXP_PATH` 即可）：
`C:\Users\zlf\WorkBuddy\2026-10-18-18-31-24\qa\probe_03_e2e.py`、`probe_03_edge.py`、`inject_03_stubs.py`

---

## 7. 修复追踪（PM 回填，2026-10-21，commit 见 git log）

被测版本 `9e1f4be` 的缺陷在 `992a221`（Ticket 05）与随后的 05b 补丁中处理。复测请以「被测 commit + 期望表 sha256」成对核对（`qa/e2e_stub.py` 开头已打印）。

| 条目 | 状态 | 修复位置 | 复验方式 |
|---|---|---|---|
| P1-1 空白问题返回 Q1 数据 | ✅ 已修 | `StubLlmClient.extractQuestion` 空白返回空串；`AgentEngine.run` 入口 blank 短路 | `e2e_stub.py` 反例段「空白-空串/空格/换行」→ `sql=''`、`answer='问题为空，请描述你要查什么'` |
| P1-2 跨表维度穿到 DB + 异常原文外泄 | ✅ 已修 | `IrToSqlConverter.assertFieldsOnTable`（用 `cache.tableFields()` 做 `table.field` 作用域校验）；`SqlExecuteStep` 只回 traceId 文案 | 反例桩 `neg_cross_table.json` → 「维度字段 channel_name 不属于表 ads_trade_overview_di」；`e2e_stub.py` 反例段「跨表维度」 |
| P2-1 双意向拼接静默只取一个 | ✅ 已修 | `StubLlmClient.match()`：多条部分匹配 → 无法识别 + warn | 反例段「双意向拼接」→ `无法识别查询意图，请换个问法` |
| P2-2 DETAIL 行序不稳定 | ✅ 已修 | `appendOrderBy` 的 tieBreakers | 行序稳定性段 Q4/Q5 连跑两轮一致（上海/江苏/浙江、4K显示器/机械键盘） |
| P2-3 Q6 并列排序不稳定 | ✅ 已修 | 同上（AGG 追加维度 ASC） | 行序稳定性段 Q6 两轮一致；即使 IR 只给单键排序也稳定 |
| P2-4 维度字典 6/28、dt 恒歧义 | ◑ 部分（按设计） | `V05b` 迁移补 `channel`/`province`/`product_id` → 12 行 / 9 唯一字段；dt 单维度拒绝文案给下一步 | `SELECT COUNT(*) FROM dimension_def` = 12；「按日期查明细测试」→「只按日期查明细会跨 4 张表，请再补一个渠道/地域/商品维度或指定指标」。**剩余度量字段按定义不入维度字典**，DETAIL 维度面交 Ticket 16 |
| P2-5 max_metric/max_join 不可达 | ✅ 已修 | `seed.sql` + `V05b`：`max_metric=1`、`max_join=1` | 2 指标 → 代价闸门拒绝「指标数 2 > 1」 |
| P2-6 IR_Schema §2 与代码不一致 | ✅ 已修（05） | `IR_Schema.md` §2 record 定义重写 | 人工比对 `QueryIr.java` |
| P2-7 字段正则大小写 + IN 逗号硬切 | ✅ 已修 | `FIELD_NAME` 加 CASE_INSENSITIVE + 统一小写出 SQL（`IrToSqlConverter`、`ExistenceGate`）；IN 只认数组 | 「大写字段测试」→ `SELECT channel_name, ... WHERE dt = ?`；「IN逗号串测试」→ 显式拒绝；「IN数组测试」→ `IN (?, ?)` 2 参数 |
| 建议：期望表版本可核对 | ✅ 已落地 | `qa/e2e_stub.py` 打印期望表 sha256 + 被测 commit + 未提交改动数 | 脚本首段「== 版本 ==」 |

**复测口径补充（本票发现的环境事故）**：`git archive <commit>` 导出固定副本仍是唯一可靠办法；`target/classes` 会被并行开工的下一票覆盖，脚本里打印的 commit 只能证明工作区来源，不能证明跑的是那份字节码 —— 建议复测时在 18088 类独立端口用固定副本，并在报告里写明端口。

**未闭环、已知并有意保留**：`ratio` 字段仍存在于 `QueryIr`（已停用，遇即拒绝），保留是为了让旧 IR 得到「请改用复合指标」的明确指引而不是反序列化失败。
