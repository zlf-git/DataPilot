# DataPilot IR Schema（F-02 中间表示）

| 项 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 更新日期 | 2026-10-20 |
| 作者 | 张灵枫 |
| 关联文档 | Ontology定义.md（F-01）、评测集.md（F-00） |

> IR（Intermediate Representation）是「自然语言」与「SQL」之间的安全中间层：LLM 不直接返回 SQL 字符串，而是返回结构化的 IR；执行器只接收 IR，且 IR 中每个字段/指标/表必须落在 Ontology 白名单内。这是「白名单约束生成空间」技术路径的落地承载体。

---

## 1. 为什么需要 IR

| 问题 | IR 如何解决 |
|---|---|
| LLM 直接吐 SQL，语法/字段不可控 | IR 是受约束的结构体，字段必须落白名单 |
| 执行入口收裸 SQL = 注入/越权风险 | 执行入口只收 IR，不收 SQL 字符串 |
| 身份混进 SQL 文本 | 身份不进 IR，走传输层 token 绑定 |
| 五道闸门无处下手 | 闸门在 IR 层（存在性/权限/代价）与 SQL 层（语法/结果自检）分别校验 |

---

## 2. IR 顶层结构

```java
public record QueryIr(
    String question,              // 原始自然语言，仅审计/日志用，不进 SQL
    List<MetricRef> metrics,      // 指标（≥1，必填）
    List<DimensionRef> dimensions,// 分组维度（可空）
    List<Filter> filters,         // 筛选条件（可空）
    List<OrderBy> orderBy,        // 排序（可空）
    Integer limit                 // TopN（可空，服务端上限 1000）
) {}
```

### 2.1 MetricRef（指标引用）

```java
public record MetricRef(
    String metricId,   // 指标 ID，必须落 Ontology 指标白名单（metric_def.metric_id）
    String table       // 指标所在表，必须落表白名单
) {}
```

- `metricId` 命中 `metric_def` 后，口径 SQL（`metric_sql`）与可用维度（`dimensions`）由服务端派生，**不信任 LLM 传入的聚合/字段**。
- 未命中指标白名单时回退字段层：`MetricRef` 退化为 `{ field, agg, table }`，`field` 仍必须落字段白名单。

### 2.2 DimensionRef（维度引用）

```java
public record DimensionRef(
    String field,   // 维度字段，必须落字段白名单且属性为「维度」
    String table    // 所在表
) {}
```

### 2.3 Filter（筛选条件）

```java
public record Filter(
    String field,   // 字段，必须落字段白名单
    String table,   // 所在表
    String op,      // = != > < >= <= IN LIKE BETWEEN
    String value    // 值；IN 用逗号分隔；值优先落维度取值白名单
) {}
```

### 2.4 OrderBy（排序）

```java
public record OrderBy(
    String field,     // 排序字段（指标或维度），必须落白名单
    String direction  // ASC / DESC
) {}
```

---

## 3. IR JSON 示例

### 示例一（对应评测集 Q6：单表分组 + 排序 + 筛选）

问题：「10月19日各渠道的支付转化率分别是多少？按从高到低排序」

```json
{
  "question": "10月19日各渠道的支付转化率分别是多少？按从高到低排序",
  "metrics": [
    { "metricId": "支付转化率", "table": "ads_channel_conversion_di" }
  ],
  "dimensions": [
    { "field": "channel_name", "table": "ads_channel_conversion_di" }
  ],
  "filters": [
    { "field": "dt", "table": "ads_channel_conversion_di", "op": "=", "value": "2026-10-19" }
  ],
  "orderBy": [
    { "field": "pay_conversion_rate", "direction": "DESC" }
  ],
  "limit": null
}
```

### 示例二（对应评测集 Q15：跨表关联 + 占比）

问题：「10月19日各渠道GMV占当天总GMV的比例分别是多少？」

```json
{
  "question": "10月19日各渠道GMV占当天总GMV的比例分别是多少？",
  "metrics": [
    { "metricId": "GMV（渠道/地域）", "table": "ads_channel_conversion_di" },
    { "metricId": "GMV（全局）", "table": "ads_trade_overview_di" }
  ],
  "dimensions": [
    { "field": "channel_name", "table": "ads_channel_conversion_di" }
  ],
  "filters": [
    { "field": "dt", "table": "ads_channel_conversion_di", "op": "=", "value": "2026-10-19" },
    { "field": "dt", "table": "ads_trade_overview_di", "op": "=", "value": "2026-10-19" }
  ],
  "orderBy": [],
  "limit": null
}
```

---

## 4. 安全边界（IR 与白名单绑定）

| 校验点 | 规则 | 违例处理 |
|---|---|---|
| 表名 | 每个 `table` 必须落表白名单（4 张 ADS） | 拒绝执行 |
| 字段名 | 每个 `field` 必须落字段白名单 | 拒绝执行 |
| 指标名 | 每个 `metricId` 必须落指标白名单 `metric_def` | 回退字段层或拒绝 |
| 维度属性 | `DimensionRef.field` 必须标记为「维度」属性 | 拒绝执行 |
| 聚合 | `agg` 由服务端按指标口径派生，不采信 LLM | 覆盖 LLM 传值 |
| 身份 | 不进 IR，走传输层 token 绑定 | — |

---

## 5. IR 与五道闸门的映射

| 闸门 | 作用层 | 说明 |
|---|---|---|
| ① 语法闸门 | SQL 层 | IR→SQL 后，JSqlParser 解析校验语法 |
| ② 代价闸门 | IR 层 | 预估复杂度 = 指标数 × 维度数 × 表 join 数，超阈值拒绝 |
| ③ 存在性闸门 | IR 层 | 表/字段/指标落白名单校验（见上表） |
| ④ 权限闸门 | IR 层 | 只读表白名单 + 只读账号执行 |
| ⑤ 结果自检 | 结果层 | 执行后空结果/异常值自检 |

---

## 6. 与后续环节的关系

- **F-03 模板**：Prompt 模板要求 LLM 按本 Schema 输出 JSON，并配 Structured Output 强制约束。
- **F-04 闸门**：存在性/权限/代价三道闸门直接在 IR 上校验。
- **F-05 执行**：执行器把 IR 转成受控 SQL（参数化绑定失效场景下靠白名单 + 只读账号兜底），走 StarRocks 只读账号执行。
