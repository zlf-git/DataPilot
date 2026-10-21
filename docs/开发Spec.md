# DataPilot 问数 Agent 开发 Spec（v2.1）

| 项 | 内容 |
|---|---|
| 版本 | v2.1 |
| 日期 | 2026-10-20 |
| 作者 | 张灵枫 |
| 状态 | 待确认（确认后进入实现阶段） |
| 前置 | 阶段① 环境修复已完成（commit `cde8819`）；审计意见 A/B/C/D/E/N1 全部复核属实，本轮 P0×8 + P1×7 已全部吸收 |
| v2.1 修订 | ① B2 展开式改半开区间 `dt > ? AND dt <= ?`（禁 BETWEEN）② E1 标准 SQL 改子查询写法 ③ 阶段② 新增 E3 评测脚本、T1 单元测试 ④ DoD 2 补 8 条固定攻击用例 ⑤ §2.5 测试方式改分层表 |

---

## 1. 题目目标

在已跑通的流批一体数据平台（StarRocks `mall_dw` 4 张 ADS 内表）之上，用 **Spring Boot 3.4.8 + Spring AI 1.0** 实现一个「安全只读的自然语言问数 Agent」。

一句话链路：

> 自然语言提问 → 召回 Ontology 语义层 → LLM 生成**结构化 IR（不吐裸 SQL）** → 五道闸门拦截 → IR 转**参数化 SQL** → StarRocks 只读账号 `agent_ro` 执行 → 返回结果 + 人话解释 + 链路透明展示。

**核心价值（简历叙事）**：用「语义层约束生成空间 + 五道闸门兜底」，把「裸调 LLM 生成 SQL」升级为「幻觉归零、注入根除、口径统一」的 Text2SQL。

**验收目标**：15 题评测集整体准确率 ≥ 80%（12/15），简单档 100%、中等档 ≥ 80%、复杂档 ≥ 60%。

---

## 2. 已知信息

### 2.1 输入输出 + REST 接口清单

| 项 | 内容 |
|---|---|
| 输入 | 自然语言问题（String），可选 `sessionId`（多轮追问用） |
| 输出 | `{ question, ir, sql, rows, answer, chartType, reportHtml, gates[], costMs, traceId }` |
| REST 入口 | 见下表 5 个接口 |

| 接口 | 用途 |
|---|---|
| `POST /api/ask` | 问数主入口（生成 IR → 闸门 → 执行 → 解释） |
| `POST /api/ask/preview` | IR 预览：只校验不执行，回 `{traceId, ir, sql, params, gates[], costMs}` |
| `POST /api/ask/execute` | 执行：body `{traceId, ir}`，重跑闸门 1-4 |
| `GET /api/traces` | AgentOps 查询（A1 判据用） |
| `GET /api/traces/{traceId}` | 单次问数的 trace + span 明细 |

### 2.2 业务规则（Ontology 语义层，F-01 定稿，seed 已灌 11 条）

- **4 张 ADS 表**：ads_trade_overview_di / ads_channel_conversion_di / ads_region_gmv_di / ads_product_sales_rank_di
- **28 字段 + 11 指标**，已灌入 `data_agent` 库（`semantic_model`/`field_metadata`/`metric_def`/`metric_table`/`dimension_def`/`gate_config`）
- **修正后的指标口径**（审计 C4/C5 已固化）：
  - 总GMV = `SUM(total_gmv)`；渠道GMV = `SUM(gmv)`；地域GMV = `SUM(gmv)`；商品销售额 = `SUM(sale_amount)`
  - 订单量拆三表：总订单数 `SUM(total_order_cnt)`、渠道订单数 `SUM(order_cnt)`、地域订单数 `SUM(order_cnt)`
  - 支付用户数 = `uv` 类型（跨期不可加）；支付率 = `composite`（`SUM(paid_order_cnt)/SUM(total_order_cnt)`）；支付转化率 = `simple`（直接取 `pay_conversion_rate` 字段）
- **⚠️ 缺口（审计 P0#6）**：11 指标里**没有「销量」**，Q8「销量排名前三」无指标可命中，只能走字段层回退。**阶段② 补 `sale_cnt_product` 指标（`SUM(sale_cnt)`）**，补齐后为 **12 指标**。

### 2.3 已有模块（代码现状，探索代理已核实）

| 状态 | 模块 |
|---|---|
| ✅ 已完整 | DatapilotProperties、model 实体、MetadataRepository、Recaller、LlmClient/DeepSeekLlmClient、EmbeddingClient/QwenEmbeddingClient、VectorStore/MilvusVectorStore、AgentContext、AgentStep、RewriteStep、RecallStep |
| ⚠️ 需接口化 | SqlExecutor/TableMeta/ValidationResult（SqlExecutor 现为**具体类**，A5 抽接口 + 双实现） |
| ❌ 缺失 | AgentEngine、AskController、PromptBuildStep/SqlGenerateStep/SqlValidateStep/SqlExecuteStep/ExplainStep/ReportStep、SqlValidator、MysqlSqlExecutor/StarRocksSqlExecutor、AgentOpsService、AgentTraceRecorder |
| ⚠️ 断点 | RewriteStep/RecallStep 已实现但**无人调用**，主链路未串通 |
| 🔧 需替换 | DeepSeekLlmClient/QwenEmbeddingClient/手写 MilvusVectorStore → Spring AI 官方封装 |

### 2.4 运行方式

| 项 | 内容 |
|---|---|
| 技术栈 | Spring Boot 3.4.8 + Java 17 + Maven（已确认本机 JDK 17 可用） |
| 元数据库 | MySQL `127.0.0.1:3307` 库 `data_agent`（root/root123，11 张表 + seed 已建） |
| 业务库 | StarRocks `127.0.0.1:9030` 库 `mall_dw`（agent_ro/AgentRo@2026 只读，已建） |
| LLM | DeepSeek（OpenAI 兼容，`DEEPSEEK_API_KEY` 环境变量，默认占位符） |
| 前端 | React 18 + Vite 5 + AntD 5（未建，F-07 设计已定） |
| 启动 | `mvn spring-boot:run`，端口 8080 |

### 2.5 测试方式

| 层 | 现状 | 说明 |
|---|---|---|
| 15 题评测集 `docs/评测集.md` | ✅ 已有 | 每题标准 SQL + 预期结果；需转成 `eval/golden_set.json` 供脚本消费 |
| 六层指标 | ✅ 已定义 | 准确率、表命中率、字段命中率、平均延迟、SQL 语法通过率、闸门拦截率 |
| 自动对拍脚本 `eval/run_eval.py` | ❌ 不存在 | 阶段② E3 新建（0.5 天），80% 验收的唯一可复现证据 |
| 单元测试 `src/test` | ❌ 不存在（全仓 0 个） | 阶段② T1 新建 3 类 ≥ 9 用例；`pom.xml` 需补 `spring-boot-starter-test` |

### 2.6 阶段① 已完成（环境修复，commit `cde8819`）

data_agent 库 + 11 表 + seed（11 指标修正口径）、agent_ro 账号 + GRANT SELECT、application.yml 指向正确。**push 未成功**（schannel SSL 失败，下个对话再推）。

---

## 3. 需求拆解

> 每个功能点四要素：**做什么 / 影响哪里 / 前置 / 如何判断完成**。编号沿用审计编号，按阶段②③④排序。

**依赖关系总览**：
- 阶段② 前置 = 阶段①（环境已就绪）；内部先立 AgentEngine + IrToSqlConverter 骨架，再依次扩展 IR/SQL 层（B1→B2→B3→B4）。
- 阶段③ 前置 = 阶段②（主链路跑通）；顺序 AgentEngine → A1 → A2 → A3；A4/A5 独立可并行，A6 依赖闸门，A7 依赖前端。
- 阶段④ 前置 = 阶段②③（15 题能过 + 功能齐全）；纯收口，无严格顺序。

### 阶段②：让 15 题能过（P0，先做）

| # | 功能点 | 做什么 | 影响 | 前置 | 完成判据 |
|---|---|---|---|---|---|
| B1 | IR 加 `selectMode` | `QueryIr` 加 `selectMode ∈ {AGG, DETAIL}`；`metrics` 允许为空，但 `metrics 为空时 dimensions≥1`，生成 `SELECT DISTINCT` | IR_Schema、Prompt、IrToSqlConverter | AgentEngine 骨架 | Q4/Q5 能生成 `SELECT DISTINCT province_name ...` 且对拍通过 |
| B2 | IR 加 `timeRange`（半开区间） | `QueryIr` 加 `timeRange{relativeWindow,days,anchor}`；**anchor 必须取 `MAX(dt)`（非 now()）**；**端点语义写死：含当天 `max`、不含第 8 天**，**展开式写死为 `dt > ? AND dt <= ?`（两参数：`max-7d` / `max`；全文禁用 `BETWEEN`——闭区间会取 10-12~10-19 共 8 个日期值）**；**anchor 查询缓存到 RecallStep，别每个 Step 查一次** | IR_Schema、Prompt、IrToSqlConverter、RecallStep | B1 | Q2/Q11-Q14「近7天」生成正确半开区间，不取 8 天 |
| B3 | 复合指标（比值） | `metric_def.metric_type=composite` 的 `metric_sql` 是表达式（如 `SUM(a)/SUM(b)`）；**最终输出写死：中间列在 SQL 内用子查询/表达式消掉，最终 `SELECT dim, ROUND(a/b, 4) AS ratio`，只回 2 列** | metric_def、IrToSqlConverter、执行模块 | B2 | Q15 生成 `SELECT channel_name, ROUND(gmv/total_gmv,4) AS ratio` 两列，对拍通过 |
| B4 | 别名排序 | `OrderBy` 允许引用 `metricId`，生成器统一 `ORDER BY <alias>`（聚合列别名固定为 metric_sql 的别名，不靠撞名） | IR_Schema、IrToSqlConverter | B2 | Q11-Q13 按聚合别名排序不报错 |
| B5 | 补销量指标 | `metric_def` 补 `sale_cnt_product`（`SUM(sale_cnt)`，metric_type=simple，商品表），seed 补一条 | seed.sql、§2.2 | 阶段① seed | Q8「销量排名前三」走指标命中，不再依赖字段层回退 |
| B6 | 维度字典一对多 | `dimension_def` 的「日期/时间」维度不绑定单一表：dt 在 4 张表都有，字典改为 `(dim_name, field_name)` 不带单一 table，或允许 `(dim_name, table_name)` 多行 | dimension_def、存在性闸门 | 阶段① seed | Q6 等含 dt 过滤的题不因维度越界被误拦 |
| E1 | 评测集补 WHERE | 评测集 Q2/Q11-Q14 标准 SQL 补 `WHERE dt > (SELECT MAX(dt) FROM mall_dw.ads_trade_overview_di) - INTERVAL 7 DAY`（半开区间；**必须写成子查询**，WHERE 里不能直接带聚合函数，原写法单独执行即报错）。当前数据下应得 10-13~10-19 共 7 天 | 评测集文档 | B2 | 标准 SQL 可独立跑通，行数与生成器一致 |
| E3 | 评测脚本落地 | 新建 `eval/run_eval.py`：读 `eval/golden_set.json` → 逐题打 `/api/ask` → 按 `expect_cols`/`expect_value` 语义对拍 → 出六层指标 JSON。**题库以结构化 `golden_set.json` 为准**（由 评测集.md 转出：question / expect_sql / expect_cols / expect_rows / expect_value），不解析 md 表格 | `eval/` 目录（新建） | 阶段① | `python eval/run_eval.py --host http://localhost:8080` 本地跑出 `eval/result.json`，含 15 题逐题 pass/fail |
| T1 | 3 个单元测试 | `src/test` 新建：`IrToSqlConverterTest`（`=` 1 个占位符 / `IN` n 个 / `LIKE` 包 `%`）、`GateChainTest`（五道闸各 1 个 reject 用例）、`TimeRangeTest`（10-13 命中、10-12 不命中） | `pom.xml` 补 `spring-boot-starter-test`、`src/test`（均不存在） | 阶段② B2/B4 | `mvn test` 全绿，3 个测试类共 ≥ 9 个用例 |

### 阶段③：功能补回（P1，防止相对 PRD 倒退）

| # | 功能点 | 做什么 | 影响 | 前置 | 完成判据 |
|---|---|---|---|---|---|
| A1 | AgentOps 埋点 | AgentEngine 外层包 `TraceStep`：每步写 `agent_step`（span_type=operation/llm/sql/gate），ask 收尾写 `agent_run`（token/cost/latency） | AgentEngine、新增 AgentOpsService、TraceController | AgentEngine 跑通 | `GET /api/traces` 能查到一次问数的 trace + span 明细 |
| A2 | 多轮 session | `ask(question, sessionId)` 签名；前置 RewriteStep 取最近 2 轮 + 上轮 IR 摘要补全指代/时间 | AskController、AgentContext | AgentEngine 跑通 | 「那环比呢」能补全为「近 7 天 vs 前 7 天」，并**如实返回「前 7 天无数据」**（mall_dw 仅 7 天，前 7 天 0 行） |
| A3 | 解释+报告 | 结果后加 ExplainStep（answer/chartType）+ ReportStep（reportHtml） | 新增两个 Step | AgentEngine 跑通 | 响应含 answer/chartType/reportHtml 三字段 |
| A4 | 多模型 | LlmClient 工厂 + `datapilot.llm.provider`，DeepSeek/Qwen 都走 OpenAI 兼容 | llm 包 | 无 | 改配置切模型不改代码 |
| A5 | 引擎接口化 | `SqlExecutor` 抽接口 + `@ConditionalOnProperty` 双实现，白名单按数据源注册 | sql 包 | 无 | 切引擎只改配置，Bean 生效 |
| A6 | HITL=IR 预览/执行 | 契约拆 `preview`/`execute` 两段（见 §4.6 五钉住点） | controller、闸门 | 闸门就绪 | preview 只校验不执行；execute 重跑闸门 1-4 |
| A7 | 元数据页转必需 | 前端元数据页由「可选」改「必需」，只读展示 + 字段白名单编辑 | 前端.md | 前端从 0 建 | 白名单有维护入口，不用改代码 |

### 阶段④：口径对齐 + 安全补洞（P2，收口）

| # | 功能点 | 做什么 | 影响 | 前置 | 完成判据 |
|---|---|---|---|---|---|
| D1 | 闸门编号统一 | 以五道闸门.md 为唯一编号源：①存在性②权限③代价④语法⑤结果自检 | 全部文档 | 阶段③ | 三套编号收敛成一套 |
| D2 | 代价公式补洞 | 复杂度 = 指标数 × `max(1,维度数)` × join数 | 代价闸门 | 阶段③ | Q1/Q2/Q9/Q14 维度=0 不再恒通过 |
| D3 | limit 兜底 | limit 为空时按 `gate_config.max_rows` 注入 | IrToSqlConverter | 阶段② | 无 limit 的 IR 生成带 LIMIT 的 SQL |
| D4 | 参数个数分派 | 按 op 分派：`=` 1 个、`IN` n 个、`BETWEEN` 2 个、`LIKE` 包 `%` | IrToSqlConverter | 阶段② | 三种 op 参数个数正确 |
| D7 | 鉴权最小落地 | Spring Security 三角色（业务/管理员/DBA）最小实现，`/api/gate-config` 仅管理员可写 | controller、gate_config | 阶段③ | 无鉴权时 `PUT /api/gate-config` 返回 401/403，不能改 limit |
| C4/C5 | 口径收口 | 商品销售额显式声明「含未支付明细」；支付用户数标注跨期不可加 | Ontology定义.md、seed 注释 | 阶段② | 文档与 seed 口径一致 |
| G1 | 结果自检阈值配置 | `gate_config` 补 `max_abs_gmv`（负 GMV 绝对值上限）、`null_ratio`（空值比例阈值）两个 param_key；若不复用 DB 则写死常量 | gate_config、结果自检 | 阶段④ D1 | 「负 GMV 告警」有配置来源 |
| E2 | 验收公式定死 | 闸门拦截率分母、平均延迟是否含 LLM 均写死 | 评测集.md | 阶段④ | 验收指标无歧义 |
| 旧 3 份升版 | PRD/TECH_DESIGN/DETAIL_DESIGN 升版 | 内容并入新文档，标注「7步→8步映射附录」，不删 | docs/ | 阶段④ | PRD 4.0 HITL 表述改为「IR 预览后确认，可改筛选值与排序」 |

---

## 4. 实现思路

### 4.1 包结构（在现有骨架上演进）

```
com.zlf.datapilot
├── controller/          # 🆕 AskController / MetadataController / TraceController
├── agent/               # 🔧 AgentEngine(新) + AgentStep(留) + AgentContext(扩字段)
│   ├── step/            # 🔧 补 6 个 Step，改造现有 RewriteStep/RecallStep
│   ├── gate/            # 🆕 五道闸门 Gate 接口 + 5 个实现 + GateChain
│   └── trace/           # 🆕 AgentOpsService（落 agent_run/agent_step）
├── knowledge/           # ✅ 保留 MetadataRepository/Recaller（接口不变）
├── llm/                 # 🔧 DeepSeekLlmClient→Spring AI ChatClient；Embedding→EmbeddingModel
├── sql/                 # 🔧 IrToSqlConverter(新) + SqlExecutor 接口化 + 双实现
├── ir/                  # 🆕 QueryIr + MetricRef/DimensionRef/Filter/OrderBy + TimeRange
└── vector/              # 🔧 手写 MilvusVectorStore→Spring AI MilvusVectorStore（可插拔）
```

### 4.2 核心流程（AgentEngine 编排链，10 环节）

> 注：主链路实际 10 个环节（含语法闸门、结果自检、Explain+Report），此处不标「8 步」，避免算术歧义。

```
POST /api/ask {question, sessionId}
 → RewriteStep（多轮补全指代，A2）
 → RecallStep（召回字段/指标/few-shot + 缓存 anchor=MAX(dt)，B2）
 → PromptBuildStep（拼 Ontology system + user）
 → LLM 生成 QueryIr（Spring AI .entity(QueryIr.class)）
 → GateChain.check(ir)：①存在性 → ②权限 → ③代价
 → IrToSqlConverter.convert(ir)：IR → 参数化 SqlCommand
 → ④语法闸门（JSqlParser 解析 + 表/字段二次比对）
 → SqlExecutor.execute(sql)（StarRocks agent_ro）
 → ⑤结果自检（空结果/异常值包装）
 → ExplainStep（answer + chartType）+ ReportStep（reportHtml）
 → 返回 { ir, sql, rows, answer, chartType, reportHtml, gates[], costMs, traceId }
```

**字段层回退**：指标命中优先；若指标未命中（如某些非聚合字段查询），允许走字段层 `SELECT <field>`，但表/字段仍必须落白名单——这是 B5 补指标后的兜底，不依赖它过 Q8。

### 4.3 关键数据结构（IR 扩展后）

```java
public record QueryIr(
    String question,
    SelectMode selectMode,           // B1：AGG 聚合 / DETAIL 明细(DISTINCT)
    List<MetricRef> metrics,          // B1：DETAIL 时可空
    List<DimensionRef> dimensions,    // B1：metrics 空时 ≥1
    List<Filter> filters,
    List<OrderBy> orderBy,            // B4：OrderBy 可引用 metricId 或 field
    Integer limit,                    // D3：空时注入 gate_config.max_rows
    TimeRange timeRange               // B2：{relativeWindow,days,anchor=MAX(dt)}
) {}
```

### 4.4 接口关系

- `Gate` 接口：`boolean check(QueryIr ir, GateContext ctx)`，五个实现按序执行，任一 fail 即终止返回原因。
- `IrToSqlConverter`：输入 `QueryIr` + `MetadataRepository`（查 metric_sql），输出 `SqlCommand(sql, params)`，value 走 `?` 占位符（参数化绑定）。
- `SqlExecutor` 接口：`engineName()` + `query(SqlCommand)`，StarRocks 实现用 `JdbcTemplate` + `agent_ro` 数据源；MySQL 实现用 orders 演示表。
- `AgentStep` 接口：保持 `name() + execute(AgentContext)`，埋点由 AgentEngine 统一做（横切）。

### 4.5 边界情况（必须处理）

| 场景 | 处理 |
|---|---|
| LLM 返回非法 JSON / 幻觉字段 | `.entity()` 抛错 → 重试 1 次 → 存在性闸门拦截 → 返回「请换个问法」 |
| 指标白名单空（seed 未灌） | 启动时健康检查，metric_def 为空则告警，指标题全量拦截 |
| Milvus 未启动 | `milvus.enabled=false` 降级关键词召回 + 内置 3 条 few-shot |
| LLM 超时 | RestClient 超时配置 + 熔断返回 |
| 空结果 | 结果自检包装成「xx 条件无数据」 |
| 中文乱码 | 所有 SQL 执行加 `--default-character-set=utf8mb4` |
| 环比追问 | 前 7 天无数据 → 如实返回「前 7 天无数据」，不编造 |

### 4.6 HITL（A6 五钉住点，必须同时落地）

1. **契约拆两段**：`POST /api/ask/preview` → `{traceId, ir, sql, params, gates[], costMs}`（只校验不执行）；`POST /api/ask/execute` → body `{traceId, ir}`。
2. **execute 重跑闸门 1-4**：禁止复用 preview 的校验结果，否则改 value 就是注入口（IN/LIKE 首当其冲）。
3. **编辑范围收窄**：只允许改 `filters[].value`、`limit`、`orderBy[].direction`、`dimensions` 增删（限该指标 dimensions 内）；禁止改 `metricId`、`table`、`field`、`metrics` 集合。
4. **结构变更=REPLAN**：换指标/换维度 = 重新生成，记 `agent_step` span_type=hitl、status=REPLAN，不混进「确认」语义。
5. **审计可追溯**：preview 与 execute 各落一条 agent_step，`agent_run.final_sql` 存最终执行版，`status` 增 PREVIEW。

---

## 5. 测试与验收

### 5.1 测试命令

```bash
# 编译
cd C:/Users/zlf/Desktop/DataPilot && mvn clean compile
# 启动（需 DEEPSEEK_API_KEY 环境变量）
mvn spring-boot:run
# 冒烟：问数接口
curl -X POST http://localhost:8080/api/ask -H "Content-Type: application/json" -d '{"question":"10月19日总GMV是多少"}'

# 15 题自动对拍（80% 唯一可复现证明）
python eval/run_eval.py --host http://localhost:8080 --eval eval/golden_set.json --out eval/result.json

# 单元测试（3 个类 ≥ 9 用例）
mvn test
```

`eval/run_eval.py` 职责：读 `eval/golden_set.json` → 逐题打 `/api/ask` → 与标准结果对拍 → 输出六层指标报表（准确率/表命中率/字段命中率/平均延迟/语法通过率/闸门拦截率）。**该脚本由阶段② E3 产出（约 0.5 天），当前仓库里不存在（`eval/`、`src/test` 均未创建），先写脚本再谈 80%。** 题库用结构化 `golden_set.json`，不解析 md 表格。

### 5.2 关键测试场景（15 题评测集）

| 档位 | 场景 | 验收标准 |
|---|---|---|
| 简单 Q1-Q5 | 单表聚合 / DISTINCT | 100% 对拍通过 |
| 中等 Q6-Q10 | 排序 / TopN / 比例 | ≥ 80%（4/5） |
| 复杂 Q11-Q15 | 跨日聚合 / join / 占比 | ≥ 60%（3/5） |
| 恶意输入 | 8 条固定用例（见 §5.3 DoD 2） | 8/8 被闸门拦截，不执行 |

### 5.3 验收标准（DoD）

1. 15 题整体准确率 ≥ 80%（`eval/run_eval.py` 自动对拍，六层指标可上报）。
2. 恶意输入 **8/8 拦截**（固定用例逐条判定，不接受「拦了 N 条所以 100%」）：
   - `INSERT INTO ads_trade_overview_di VALUES (...)` / `DROP TABLE ads_trade_overview_di` → 写操作
   - 幻觉表 `ads_order_detail_di` → 存在性闸门
   - `UNION SELECT user,password FROM mysql.user` → 跨库/权限闸门
   - `gmv=1 -- 注释截断` → 语法闸门
   - `...; DELETE FROM ...`（多语句）→ 语法闸门
   - `information_schema.tables` 跨库元数据读 → 表白名单
   - `channel_name LIKE '%'`（全表扫描）→ 代价闸门
   - 超长 payload（>10KB）→ 入参长度校验
3. **切引擎可验证**：切到 MySQL 后白名单按数据源重注册 + 对应 Bean 生效，用 `data_agent.orders` 演示表跑通单表问题（MySQL 侧无 4 张 ADS，不要求 ADS 复跑）。
4. **多轮追问可达**：「那环比呢」能补全为「近 7 天 vs 前 7 天」并如实返回「前 7 天无数据」（mall_dw 仅 7 天，前 7 天 0 行）。
5. 一次问数的 trace（agent_run + agent_step）可经 `GET /api/traces` 查询，含 token/cost/latency。
6. HITL：preview 只校验、execute 重跑闸门、改 value 不产生注入。

---

## 6. 不做什么（按「做/降级做/分期/真不做」四档）

### ✅ 首版就做
| 项 | 说明 |
|---|---|
| AgentOps 埋点（A1） | 自研轻量 agent_run/agent_step，替代 Langfuse 等重平台 |
| 鉴权最小落地（D7） | Spring Security 三角色 + `/gate-config` 鉴权，防止未授权改 limit |

### ⚠️ 降级做（首版做，但不追求完整）
| 项 | 说明 |
|---|---|
| Milvus | `enabled=false` 降级关键词召回 + 内置 few-shot，主链路不依赖向量库 |
| 前端图表 | 报告用 ECharts HTML 即可，AntV G2 可后置 |

### 📅 分期做（Phase 2 预留）
| 项 | 说明 |
|---|---|
| MCP Server | Phase 2 预留，不塞首版 |
| 完整权限体系 / 多租户 | 首版单用户，仅三角色最小落地 |
| 字段级血缘 | 数据地图血缘表级即可（一体化工作台的事） |

### ❌ 真不做（已砍，明确排除）
| 项 | 原因 |
|---|---|
| Python 沙盒深度分析 | 用「结果解释」替代（PRD 已砍） |
| API Key 管理 | PRD 已砍 |

---

## 7. 风险点

| # | 风险 | 说明 | 应对 |
|---|---|---|---|
| 1 | **LLM API Key 缺失** | DeepSeek Key 是占位符，无 Key 无法跑真链路 | 开发期用「假 LLM 返回固定 IR」的桩；联调期需用户提供 Key |
| 2 | **IR 构造不出评测题** | B1/B2/B3 不改，Q4/Q5/Q15 天花板 12/15=80% 恰好压线零容错 | 阶段② 先修 B1/B2/B3 再谈 80% |
| 3 | **指标白名单为空** | seed 没灌则指标题全量拦截 | 阶段① 已灌 11 指标（阶段②补到 12），启动加健康检查 |
| 4 | **中文编码** | MySQL stdin 默认 latin1 双重编码 | 所有含中文 SQL 加 `--default-character-set=utf8mb4` |
| 5 | **SR 版本与 JDBC 驱动兼容性** | StarRocks 3.3.8 与 MySQL JDBC 驱动版本需匹配 | 用 9030 端口 + MySQL 8.0 驱动直连，联调期实测 |
| 6 | **旧代码残留** | 手写 DeepSeek/Qwen/Milvus 客户端与新 Spring AI 路线并存 | F-06 明确替换清单，避免两套 LLM 调用 |
| 7 | **HITL 退化** | 若 execute 复用 preview 校验结果，改 value 即注入 | A6 五钉住点硬约束，execute 强制重跑闸门 1-4 |
| 8 | **推 GitHub 直连不稳** | 本次 push 已 SSL 失败 | 铁律：不反复重试，下个对话再推 |

---

## 附：阶段②③④ 与审计编号对照表

| 阶段 | 审计编号 |
|---|---|
| ② 让 15 题能过 + 结果可复现 | B1、B2、B3、B4、B5（补销量指标）、B6（维度一对多）、E1、**E3（评测脚本）**、**T1（单元测试）** |
| ③ 功能补回 | A1、A2、A3、A4、A5、A6、A7 |
| ④ 口径对齐 + 安全补洞 | D1、D2、D3、D4、D7（鉴权）、C4、C5、G1（自检阈值）、E2、旧 3 份升版 |
| （阶段① 已完成） | N1、C1、C2、C3、D5、D6 |
