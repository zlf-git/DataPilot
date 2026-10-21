# DataPilot Agent 开发 Tickets

拆分自 `docs/开发Spec.md` **v2.1**，共 21 个 ticket，按依赖顺序排列，每票含预估工时（tickets 自身版本 v2.3）。

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
