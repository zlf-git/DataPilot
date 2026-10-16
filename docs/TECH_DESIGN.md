# DataPilot 技术方案设计文档

| 项 | 内容 |
|---|---|
| 文档版本 | v0.1 |
| 更新日期 | 2026-10-16 |
| 作者 | 张灵枫 |
| 关联文档 | PRD v0.1 |

---

## 1. 技术选型（定稿）

| 层 | 选型 | 理由 |
|---|---|---|
| 语言 | Java 17 | 大数据/后端生态主语言，补齐后端短板 |
| 框架 | Spring Boot 3.4.8 | 最小依赖，Web + JDBC 即可 |
| 元数据库 | MySQL | 存语义模型/字段/指标/术语，关系型最自然 |
| 业务数据引擎 | MySQL / StarRocks（可插拔） | 复用数仓强项，切换只改配置 |
| LLM | DeepSeek（OpenAI 兼容） | 非推理模型，无 think 污染，便宜够用 |
| Agent 编排 | 手写状态机 | 借鉴 StateGraph 思想，讲得深、依赖最少 |
| HTTP 客户端 | RestClient | Spring 6 内置，无需额外依赖 |
| 前端 | 极简 HTML | 演示够用，不铺 Nuxt |

---

## 2. 架构总览

```mermaid
graph LR
    U[用户] -->|自然语言| C[Controller /ask]
    C --> O[Agent 编排状态机]
    O --> S1[① 问题改写]
    S1 --> S2[② 元数据召回]
    S2 --> S3[③ Prompt 组装]
    S3 --> S4[④ SQL 生成]
    S4 --> S5[⑤ SQL 校验]
    S5 -->|通过| S6[⑥ SQL 执行]
    S5 -->|拦截| C
    S6 --> S7[⑦ 结果解释]
    S7 --> C

    S1 -.调用.-> LLM[LLM DeepSeek]
    S4 -.调用.-> LLM
    S7 -.调用.-> LLM
    S2 -.查.-> META[(元数据库 MySQL)]
    S6 -.查.-> BUS[(业务库 MySQL/StarRocks)]
```

**一句话**：Controller 收问题 → 状态机串 7 个节点 → 节点间通过 `AgentContext` 传递状态 → 执行结果经 LLM 解释后返回。

---

## 3. 模块划分（对应代码包结构）

```
com.zlf.datapilot
├── controller/    # REST 入口，收问题、返回结论
├── agent/         # 编排核心：AgentStep 接口 + AgentContext 状态 + 7 个节点实现
├── knowledge/     # 元数据层：语义模型/字段/指标/术语的 CRUD + 召回
├── llm/           # LLM 客户端：OpenAI 兼容对话接口 + DeepSeek 实现
└── sql/           # SQL 引擎：SqlExecutor 接口 + MySQL/StarRocks 实现 + 校验器
```

**职责边界**（面试会问"为什么这么拆"）：
- `agent` 只关心"流程怎么走"，不关心"表长啥样""SQL 怎么执行"
- `knowledge` 只关心"元数据在哪、怎么召回"，不碰 LLM
- `sql` 只关心"SQL 能不能跑、怎么跑"，不做业务
- 每个包依赖单向，不交叉 → 换引擎/换 LLM/换节点都不动别人

---

## 4. 核心数据流（7 步链路）

| 节点 | 输入 | 输出 | 依赖 |
|---|---|---|---|
| ① 问题改写 | 原始问题 | 规范化问题 | LLM |
| ② 元数据召回 | 规范化问题 | 相关表 + 字段 + 指标 | 元数据库 |
| ③ Prompt 组装 | 问题 + 元数据 | 完整 Prompt | —— |
| ④ SQL 生成 | Prompt | SQL | LLM |
| ⑤ SQL 校验 | SQL | 通过/拦截 | —— |
| ⑥ SQL 执行 | SQL | 结果集 | 业务库 |
| ⑦ 结果解释 | 结果集 + 问题 | 人话结论 | LLM |

**状态传递**：所有节点读同一个 `AgentContext` 对象，改完传下去（状态机思想的核心）。

---

## 5. 关键设计决策（为什么这么选，面试重点）

### 5.1 为什么手写状态机，不用 Spring AI Graph
- 5 天周期调 Spring AI Graph 只能"会用"，被问"节点状态怎么序列化、图和顺序执行的区别"就露馅。
- 手写状态机能讲清本质：状态在节点间流转 + 失败重试 + 条件分支，这些是我自己实现的，讲得深。

### 5.2 为什么 SQL 引擎可插拔
- 数仓背景的实际需求：业务数据可能在 MySQL（测试）也可能在 StarRocks（生产 ADS 表）。
- 抽 `SqlExecutor` 接口，切换引擎只改配置不改主链路——这是"数据服务化"落地的雏形。

### 5.3 为什么元数据存 MySQL，不用 StarRocks
- 元数据是"关系型小数据"（表/字段/指标/术语），需要精确查询、唯一约束、事务。
- StarRocks 是 OLAP，强在聚合大表，不适合存元数据。元数据与业务数据分离，是架构常识。

### 5.4 为什么 SQL 校验放执行前，而不是执行后
- 安全第一：只读白名单 + 表白名单 + LIMIT，在 LLM 生成 SQL 后、执行前拦截。
- 对应 PRD 的核心痛点"裸调 LLM 生成的 SQL 越权/注入"，校验器是这道防线。

---

## 6. 数据模型（元数据 4 表 + 业务样例表）

| 表 | 作用 | 关键字段 |
|---|---|---|
| `semantic_model` | 可查询的表 | table_name, table_comment, biz_domain |
| `field_metadata` | 字段 + 枚举 | table_name, field_name, field_comment, enum_values |
| `metric_def` | 指标口径（只定义一次） | metric_name, definition |
| `term_dict` | 业务术语同义词 | term, field_ref |
| `orders` | 业务样例表（演示用） | order_id, channel, sku_id, amount, status, order_time |

完整 DDL 见 `src/main/resources/schema.sql`。

---

## 7. 接口设计（REST）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ask` | 问数：`{"question":"上月销售额是多少","sessionId":"可选"}` |
| GET | `/api/tables` | 列出已登记的语义模型表 |
| POST | `/api/metadata` | 数据管理员维护元数据（表/字段/指标/术语） |

返回结构：
```json
{
  "question": "上月销售额是多少",
  "sql": "SELECT SUM(amount) FROM orders WHERE ...",
  "answer": "上月销售额为 xxx 万元",
  "data": [{"column": "value"}],
  "chartType": "card"
}
```

---

## 8. 里程碑（对应 5 天）

| 天 | 交付 |
|---|---|
| Day 1 | 骨架 + 4 元数据表 + LLM 客户端调通 |
| Day 2 | 元数据 CRUD + 召回 + 问题改写节点 |
| Day 3 | SQL 生成 + 校验 + 执行 |
| Day 4 | 结果解释 + 多轮对话 + 串 7 步 |
| Day 5 | 极简前端 + few-shot + README + 演示 |
