# DataPilot 详细设计文档

| 项 | 内容 |
|---|---|
| 文档版本 | v0.1 |
| 更新日期 | 2026-10-16 |
| 作者 | 张灵枫 |
| 关联文档 | PRD v0.1 / TECH_DESIGN v0.1 |

> 本文档把方案设计落成"照着写代码"的粒度：定死接口签名、节点逻辑、埋点方式、Prompt 模板、Milvus 集成、REST 契约。开发阶段（阶段 4）只做实现，不再做设计决策。

---

## 1. 目标与范围

- **目标**：让每个类、每个方法、每个 prompt 都定死，开发时不再纠结"这个接口长啥样"。
- **范围**：7 步核心链路 + 1 个报告节点 + AgentOps 埋点 + Milvus 召回 + 可插拔引擎。MCP 属 Phase 2，本文档不展开实现，仅保留扩展点说明。

---

## 2. 包结构与类清单

```
com.zlf.datapilot
├── DataPilotApplication.java          ✅ 已建
├── config/
│   └── DatapilotProperties.java       🆕 配置类（llm/engine/milvus/cost）
├── controller/
│   ├── AskController.java             🆕 POST /api/ask
│   ├── MetadataController.java        🆕 元数据 CRUD
│   └── TraceController.java           🆕 GET /api/traces
├── agent/
│   ├── AgentEngine.java               🆕 编排器：串节点 + 埋点 + 重试
│   ├── AgentStep.java                 ✅ 已建（接口，保持）
│   ├── AgentContext.java              🔧 需加字段
│   ├── step/
│   │   ├── RewriteStep.java           🆕 ① 问题改写
│   │   ├── RecallStep.java            🆕 ② 元数据召回
│   │   ├── PromptBuildStep.java       🆕 ③ Prompt 组装
│   │   ├── SqlGenerateStep.java       🆕 ④ SQL 生成
│   │   ├── SqlValidateStep.java       🆕 ⑤ SQL 校验
│   │   ├── SqlExecuteStep.java        🆕 ⑥ SQL 执行
│   │   ├── ExplainStep.java           🆕 ⑦ 结果解释
│   │   └── ReportStep.java            🆕 ⑧ 报告生成（P1，可插拔节点示例）
│   └── trace/
│       ├── TraceSpan.java             🆕 span 数据结构
│       └── AgentTraceRecorder.java    🆕 埋点记录器（编排器层调用）
├── knowledge/
│   ├── MetadataRepository.java        🆕 元数据 CRUD（JdbcTemplate）
│   ├── Recaller.java                  🆕 召回逻辑（关键词 + 向量）
│   └── model/                         🆕 实体：SemanticModel/FieldMetadata/MetricDef/TermDict/FewShotExample
├── llm/
│   ├── LlmClient.java                 🔧 升级返回 ChatResult
│   ├── ChatResult.java                🆕 content + token 用量
│   ├── DeepSeekLlmClient.java         🆕 RestClient 实现
│   └── EmbeddingClient.java           🆕 向量化接口 + Qwen 实现
├── vector/
│   ├── VectorStore.java               🆕 向量库抽象
│   └── MilvusVectorStore.java         🆕 Milvus 实现
├── sql/
│   ├── SqlExecutor.java               🔧 加 listTables()
│   ├── TableMeta.java                 🆕 表元数据结构
│   ├── MysqlSqlExecutor.java          🆕 查 information_schema
│   ├── StarRocksSqlExecutor.java      🆕 同 MySQL 协议
│   └── SqlValidator.java              🆕 校验器
└── ops/
    └── AgentOpsService.java           🆕 agent_run/agent_step 落库
```

**图例**：✅ 已建（Day1 骨架）｜🔧 已建需改｜🆕 待建

---

## 3. 核心接口定义（定死）

### 3.1 LlmClient（🔧 升级：返回 token 用量）

```java
public class ChatResult {
    private String content;          // 模型返回文本
    private int promptTokens;        // 输入 token
    private int completionTokens;    // 输出 token
    private int totalTokens;         // 总 token
}

public interface LlmClient {
    ChatResult chat(String systemPrompt, String userPrompt);
}
```

**为什么升级**：AgentOps 要统计 token 成本，DeepSeek `/chat/completions` 响应里的 `usage` 字段直接映射到 `ChatResult`。原骨架返回 `String` 拿不到 token，必须改。

### 3.2 SqlExecutor（🔧 加 listTables，支撑元数据召回）

```java
public interface SqlExecutor {
    String engineName();                              // mysql / starrocks
    List<TableMeta> listTables();                     // 引擎自己知道怎么列表结构
    List<Map<String, Object>> query(String sql);      // 只读查询
}
```

- MySQL/StarRocks 实现：`listTables()` 查 `information_schema.tables/columns`。
- Excel 实现（预留）：`listTables()` 解析 sheet 名+表头，`query()` 内部转 DuckDB。

### 3.3 AgentStep（✅ 保持）

```java
public interface AgentStep {
    String name();
    AgentContext execute(AgentContext ctx);
}
```

**埋点不进节点**：节点类保持纯净（单一职责），耗时/状态/token 的埋点由 `AgentEngine` 统一在节点执行前后记录——埋点是横切关注点，放编排器层。

### 3.4 新增接口

```java
// 向量化
public interface EmbeddingClient {
    List<Float> embed(String text);   // Qwen text-embedding-v3，1024 维
}

// 向量库抽象（数据源轴之外的"向量轴"，也可插拔 Milvus/PGVector）
public interface VectorStore {
    void upsert(String collection, String id, List<Float> vector, Map<String,String> meta);
    List<String> search(String collection, List<Float> queryVector, int topK);
}

// SQL 校验
public interface SqlValidator {
    ValidationResult validate(String sql, Set<String> allowedTables);
}
// ValidationResult：{ boolean pass; String reason; String normalizedSql; }
```

---

## 4. AgentContext 完整字段

| 字段 | 类型 | 已有/新增 | 写入节点 |
|---|---|---|---|
| traceId | String | 🆕 | AgentEngine 入口生成 |
| sessionId | String | 🆕 | Controller 传入 |
| userQuestion | String | ✅ | Controller |
| rewrittenQuestion | String | ✅ | ① 改写 |
| relatedTables | List<String> | ✅ | ② 召回 |
| recalledMetadata | RecalledMetadata | 🆕 | ② 召回（DDL/字段注释/指标/术语/few-shot 的集合） |
| prompt | String | ✅ | ③ 组装 |
| generatedSql | String | ✅ | ④ 生成 |
| validationResult | ValidationResult | 🆕 | ⑤ 校验 |
| queryResult | List<Map> | ✅ | ⑥ 执行 |
| answer | String | ✅ | ⑦ 解释 |
| chartType | String | ✅ | ⑦ 解释（bar/line/pie/table/card） |
| reportHtml | String | 🆕 | ⑧ 报告（P1） |
| errorMessage | String | 🆕 | 任一节点失败时 |

---

## 5. 节点详细设计（7 + 1）

### 5.1 ① RewriteStep 问题改写

- **职责**：把口语化问题规范成明确的数据查询意图（补时间范围/口径/维度）。
- **输入**：`ctx.userQuestion` → **输出**：`ctx.rewrittenQuestion`
- **依赖**：LLM
- **prompt**（见 §9 模板 P1）
- **失败处理**：LLM 调用失败直接抛异常，由 AgentEngine 记 FAILED 终止（改写失败无需重试，属上游问题）。

### 5.2 ② RecallStep 元数据召回

- **职责**：找"跟这个问题相关的表、字段、指标、术语、few-shot"。
- **输入**：`ctx.rewrittenQuestion` → **输出**：`ctx.relatedTables` + `ctx.recalledMetadata`
- **两路召回**（关键词 + 向量，互为补充）：
  1. **向量召回**：问题 embedding → Milvus `search(topK=10)` → 得相关 table/field。
  2. **关键词兜底**：问题里的词直接 LIKE 匹配 `term_dict`/`metric_def`。
- **返回结构 `RecalledMetadata`**：
  ```java
  class RecalledMetadata {
      Map<String, String> ddlMap;              // tableName -> DDL
      List<FieldMetadata> fields;              // 字段+注释+枚举
      List<MetricDef> metrics;                 // 指标口径
      List<TermDict> terms;                    // 术语映射
      List<FewShotExample> fewShots;           // 同 biz_domain 的 few-shot
  }
  ```
- **依赖**：Milvus + 元数据库（MySQL）。

### 5.3 ③ PromptBuildStep Prompt 组装

- **职责**：把召回到的元数据拼成 SQL 生成的完整 prompt（纯字符串拼接，无外部依赖）。
- **输入**：`ctx.recalledMetadata` + `ctx.rewrittenQuestion` → **输出**：`ctx.prompt`
- **组装顺序**：问题 → DDL → 字段注释枚举 → 指标口径 → 术语映射 → few-shot（见 §9 模板 P2）。

### 5.4 ④ SqlGenerateStep SQL 生成

- **职责**：调 LLM 生成只读 SELECT。
- **输入**：`ctx.prompt` → **输出**：`ctx.generatedSql`
- **依赖**：LLM
- **生成后清洗**：剥掉 ```sql 代码块围栏、首尾空白（LLM 常多输出 markdown 围栏）。
- **重试**：`ctx.validationResult` 不通过时，携带"上次 SQL + 报错原因"重新生成（AgentEngine 控制，最多重试 2 次）。

### 5.5 ⑤ SqlValidateStep SQL 校验

- **职责**：执行前拦截危险/非法 SQL（纯本地逻辑，不调 LLM）。
- **输入**：`ctx.generatedSql` → **输出**：`ctx.validationResult`
- **规则清单**见 §8。
- **不通过**：`validationResult.pass=false` + reason，AgentEngine 触发重试（回 ④），重试仍失败则终止并返回 reason。

### 5.6 ⑥ SqlExecuteStep SQL 执行

- **职责**：用可插拔引擎执行校验通过的 SQL。
- **输入**：`ctx.validationResult.normalizedSql` → **输出**：`ctx.queryResult`
- **依赖**：`SqlExecutor`（配置决定 MySQL 还是 StarRocks）
- **执行失败**：记录 errorMessage，不重试（执行报错多为数据问题，回传 LLM 收益低）。

### 5.7 ⑦ ExplainStep 结果解释

- **职责**：把结果翻译成人话结论 + 建议图表类型。
- **输入**：`ctx.queryResult` + `ctx.userQuestion` + `ctx.generatedSql` → **输出**：`ctx.answer` + `ctx.chartType`
- **依赖**：LLM
- **prompt**（§9 模板 P3），输出 JSON `{"answer":"...","chartType":"bar"}`。

### 5.8 ⑧ ReportStep 报告生成（P1，可插拔节点示例）

- **职责**：把结论+数据渲染成含 ECharts 的 HTML 报告。
- **输入**：`ctx.answer` + `ctx.chartType` + `ctx.queryResult` → **输出**：`ctx.reportHtml`
- **实现**：后端用字符串模板拼 HTML（内嵌 ECharts CDN + 数据 JSON），不引模板引擎。
- **面试点**：这个节点是"能力轴可插拔"的活例子——加报告节点不改前 7 步主链路，删掉也不影响问数。

---

## 6. AgentOps 埋点设计

### 6.1 traceId 贯穿

- `AgentEngine` 入口 `UUID.randomUUID().toString().replace("-","")` 生成 traceId，写入 `ctx.traceId`。
- 整个链路所有 span 都带这个 traceId，一次问数一条 `agent_run`。

### 6.2 埋点位置（AgentEngine 统一做）

```
AgentEngine.ask(question):
  runId = AgentOpsService.startRun(traceId, sessionId, question)   // 落 agent_run
  for step in steps:
    span = startSpan(traceId, step.name(), order)                   // 记开始时间
    try:
      ctx = step.execute(ctx)
      span.status = SUCCESS
      span.output = summarize(ctx)                                  // 输出摘要
    catch e:
      span.status = FAILED
      span.errorMsg = e.getMessage()
      if step == SqlGenerateStep && retry < 2: 继续重试
      else: break
    finally:
      span.end = now(); span.latencyMs = end - start
      AgentOpsService.saveStep(span)                                 // 落 agent_step
  AgentOpsService.finishRun(runId, status, totalLatency, totalTokens, totalCost, finalSql)
```

### 6.3 span_type 取值（对齐 agentops 层级）

| span_type | 什么时候落 |
|---|---|
| `operation` | 每个节点（①~⑧）各一条 |
| `llm` | 节点内每次 LLM 调用（改写/生成/解释），从 ChatResult 取 token |
| `sql` | SQL 执行 |
| `vector` | Milvus 召回 |

> 实现简化：首版只在节点级（operation）落 span，`llm`/`sql`/`vector` 作为节点 span 的 token_used/latency 字段冗余记录；若时间够再下沉到事件级。schema 已支持（`span_type` 字段），不影响表结构。

### 6.4 token 成本计算

- `total_cost = promptTokens/1M * 输入单价 + completionTokens/1M * 输出单价`
- 单价从 `application.yml` 的 `datapilot.cost` 读（deepseek-chat 定价，可改）。
- 每个 LLM 调用的 token 从 `ChatResult` 累加到 run 级。

---

## 7. Milvus 集成方案

### 7.1 Collection 设计

```
collection: metadata_embeddings
├── id           INT64  主键（= 元数据表记录 id + 前缀，如 table_1 / field_2）
├── text         VARCHAR 原始文本（表注释/字段注释/指标名/术语）
├── embedding    FLOAT_VECTOR  dim=1024（Qwen text-embedding-v3）
├── entity_type  VARCHAR 标量：table / field / metric / term
├── ref_table    VARCHAR 关联表名（字段/指标所属表）
└── biz_domain   VARCHAR 业务域（过滤用）
```

索引：`embedding` 用 `IVF_FLAT`（nlist=128）或 `HNSW`，`entity_type`/`biz_domain` 建标量索引。

### 7.2 Embedding 什么

表注释、字段注释、指标名、业务术语——写入 `metadata_embeddings`（元数据表每新增/更新一条，同步 upsert 向量）。

### 7.3 召回流程

```
问题 → EmbeddingClient.embed(问题) → VectorStore.search(topK=10)
     → 过滤 entity_type，拿到 table/field/metric 列表
     → MetadataRepository 查完整 DDL/注释/口径
     → 组装 RecalledMetadata
```

### 7.4 冷启动

- 首版数据量小，若无 Milvus 环境，`Recaller` 可降级为**纯关键词 LIKE 召回**（返回空向量结果也不报错）——接口抽象保证"有 Milvus 用向量、没有用关键词"，符合"可插拔"。
- Milvus 部署排 Day1 末尾，和 MySQL 一起搭。

---

## 8. SQL 校验规则清单（SqlValidator）

| # | 规则 | 拦截动作 |
|---|---|---|
| 1 | 只允许 `SELECT`（首关键字判断，去注释/去首尾空白后） | 出现 INSERT/UPDATE/DELETE/DDL → 拒绝 |
| 2 | 表名白名单 | FROM/JOIN 后表名必须 ∈ allowedTables（元数据登记过） |
| 3 | 强制 LIMIT | 无 LIMIT → 自动追加 `LIMIT 100` |
| 4 | 禁止危险函数 | `SLEEP/BENCHMARK/LOAD_FILE/INTO OUTFILE` 等 → 拒绝 |
| 5 | 禁止多语句 | 含 `;` 分隔的多条 SQL → 拒绝 |
| 6 | 禁止注释注入 | 剥离 `--`、`#`、`/* */` 后再做首关键字判断 |
| 7 | 字段级白名单（可选增强） | SELECT 列名不在登记字段里 → 警告（不硬拦，LLM 可能用别名） |

> 面试点：规则 2（表名白名单）就是 AgentOps 说的"蜜罐"——未登记的蜜罐表名一旦被引用即拦截，同时暴露幻觉与注入。

---

## 9. Prompt 模板全集

### P1 问题改写

```
[system]
你是一个数据查询意图规范化助手。把用户的口语化问题改写成一句规范、完整的数据查询，
补全缺失的时间范围（如"上月"）、指标口径、统计维度。只输出改写后的问题，不要解释。

[user]
问题：{userQuestion}
```

### P2 SQL 生成

```
[system]
你是 MySQL SQL 专家。根据给定的表结构、字段注释、业务口径和示例，生成一个只读 SELECT 查询。
规则：
1. 只输出 SQL 本身，不要解释，不要 markdown 代码块围栏。
2. 只允许 SELECT，禁止任何写操作。
3. 只使用给出的表名和字段名，禁止编造不存在的表或字段。
4. 查询末尾加 LIMIT 100。
5. 金额字段求和用 SUM，时间过滤注意 BETWEEN 边界。

[user]
问题：{rewrittenQuestion}

表结构（DDL）：
{ddl}

字段注释与枚举：
{fieldComments}

指标口径定义：
{metricDefs}

业务术语映射：
{terms}

参考示例（问题 -> SQL）：
{fewShots}

请生成 SQL：
```

### P3 结果解释

```
[system]
你是数据分析师。根据查询结果，用简洁中文回答用户问题，点出关键数字，
并给出最合适的图表类型（bar/line/pie/table/card 之一）。
只输出 JSON，格式：{"answer":"...","chartType":"bar"}

[user]
用户问题：{question}
执行的 SQL：{sql}
查询结果（前 50 行）：{result}
```

### P4 few-shot 格式（few_shot_example 表里的 question/sql 对）

```
Q: 上月各渠道销售额
SQL: SELECT channel, SUM(amount) AS sales FROM orders WHERE order_time >= DATE_SUB(CURDATE(), INTERVAL 1 MONTH) AND status = 2 GROUP BY channel;
```

---

## 10. 数据访问层（MetadataRepository）

| 方法 | 说明 |
|---|---|
| `List<SemanticModel> listTables()` | 已登记的表 |
| `List<FieldMetadata> listFields(String tableName)` | 某表字段 |
| `List<MetricDef> listMetrics(String bizDomain)` | 指标（按业务域过滤） |
| `List<TermDict> listTerms()` | 术语全量 |
| `List<FewShotExample> listFewShots(String bizDomain)` | few-shot |
| `saveTable/saveField/saveMetric/saveTerm` | 元数据维护 CRUD |

用 `JdbcTemplate` 实现，不引 MyBatis/JPA（最小依赖）。

---

## 11. REST 接口契约

### POST /api/ask

```json
请求：{ "question": "上月各渠道销售额是多少", "sessionId": "可选" }

响应：
{
  "traceId": "a1b2c3...",
  "question": "上月各渠道销售额是多少",
  "sql": "SELECT channel, SUM(amount) AS sales FROM orders WHERE ...",
  "answer": "上月抖音渠道销售额 12.3 万，天猫 8.1 万...",
  "data": [ {"channel":"01","sales":123000.00}, ... ],
  "chartType": "bar",
  "reportHtml": "<html>...</html>"
}
```

### GET /api/traces?traceId=xxx（AgentOps）

```json
{
  "run": { "traceId":"...", "status":"SUCCESS", "totalLatencyMs":4200, "totalTokens":3800, "totalCost":0.0062 },
  "steps": [ {"stepName":"rewrite","status":"SUCCESS","latencyMs":800,"tokenUsed":500}, ... ]
}
```

### 元数据维护

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/tables` | 列出已登记表 |
| POST | `/api/metadata` | 维护表/字段/指标/术语 |

---

## 12. 错误处理与重试

| 场景 | 处理 |
|---|---|
| ① 改写 LLM 失败 | 终止，记 FAILED，返回"问题无法理解" |
| ④ SQL 生成失败 / ⑤ 校验不通过 | 携带报错回 ④ 重生成，**最多重试 2 次**，仍失败终止并返回 reason |
| ⑥ SQL 执行失败 | 不重试，记 errorMessage，返回"查询执行失败" |
| ⑦ 解释 LLM 失败 | 降级：直接返回原始数据 + 空解释，不阻断 |
| Milvus 不可用 | Recaller 降级纯关键词召回（§7.4） |

---

## 13. 开发顺序映射（对应 5 天里程碑）

| Day | 实现内容 | 对应类 |
|---|---|---|
| Day 1 | 骨架 + 配置类 + 元数据表建好 + LLM/Embedding 调通 | DatapilotProperties, DeepSeekLlmClient, ChatResult, EmbeddingClient, schema.sql |
| Day 2 | 元数据 CRUD + 召回 + ①改写 | MetadataRepository, Recaller, MilvusVectorStore, RewriteStep |
| Day 3 | ③组装 + ④生成 + ⑤校验 + ⑥执行 | PromptBuildStep, SqlGenerateStep, SqlValidator, MysqlSqlExecutor, SqlExecuteStep |
| Day 4 | ⑦解释 + 多轮 + 串 7 步 + AgentOps 埋点 | ExplainStep, AgentEngine, AgentTraceRecorder, AgentOpsService |
| Day 5 | ⑧报告 + 极简前端 + few-shot + README + 演示 | ReportStep, AskController, 前端 HTML |
