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
