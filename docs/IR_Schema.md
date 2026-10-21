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

---

## 7. Ticket 03 新增字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `selectMode` | `AGG` \| `DETAIL` | 否（空时按 metrics 推断） | AGG=聚合（metrics 必填，dimensions 非空即 GROUP BY）；DETAIL=明细（metrics 必须为空，dimensions≥1，生成 SELECT DISTINCT） |
| `ratio` | `{numerator, denominator}` | 否 | 占比语义声明，分子/分母各是一个 metricId。**让「各渠道GMV」与「各渠道GMV占比」在 IR 层可区分**；Ticket 03 遇到即显式拒绝（占比计算归 05） |

补充契约：

1. `orderBy.field` 取值域 = **metricId ∪ 字段名**。先按 metricId 解析成 SELECT 别名（`ORDER BY gmv_channel DESC`），解析不到再当字段名；两者都不在允许集（AGG=分组维度∪指标别名，DETAIL=DISTINCT 列）时显式报错。
2. `limit` 为空时由生成器注入 `gate_config.max_rows`（默认 1000），不是无限返回。
3. DETAIL 的表由 `dimension_def` 的 field→table 反查：多个维度/过滤字段取候选表交集，交集为空报「没有共同所属的表」，多于一张报「候选表 [...]，请补充更具体的维度」——**不猜表**。
4. `FilterOp` 只保留 `EQ/IN/LIKE/GT/GE/LT/LE`，BETWEEN 已删（闭区间与 04 的半开区间冲突）。参数个数：EQ/GT/GE/LT/LE 各 1 个，IN 按元素数展开，LIKE 绑 `%value%`。

---

## 8. Ticket 04 新增字段：timeRange（时间窗口）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `timeRange.relativeWindow` | `LAST_N_DAYS` | 否 | 相对窗口类型；只支持「近 N 天」，其余取值显式拒绝（不做猜测） |
| `timeRange.days` | int | 是（有 timeRange 时） | 窗口天数（含锚点当天），1~366；越界显式拒绝 |
| `timeRange.anchor` | `yyyy-MM-dd` | 否 | **解析结果而非模型输入**：由数据 `MAX(dt)` 算出后回填；模型若给了值与数据最新日不一致直接拒绝 |

生成契约（**全文禁 BETWEEN**）：

```sql
-- timeRange{relativeWindow:"LAST_N_DAYS", days:7} + anchor=2026-10-19
WHERE dt > ? AND dt <= ?        -- 参数：['2026-10-12', '2026-10-19']
```

1. **为什么不用 BETWEEN**：`BETWEEN '10-12' AND '10-19'` 是闭区间，会取 10-12~10-19 共 **8** 天，与「近 7 天」不符（审计 P0#1）。半开下界 + 闭合上界才是 7 天。
2. **为什么锚点取 `MAX(dt)` 而不是 `now()`**：数据滞后一天时 `now()` 会把窗口整体前移，最后一天落在空分区，GMV 凭空少一天。锚点锚在数据上，口径才稳定可复现。
3. **锚点失败即拒绝**：表无 `dt` 列 / `dt` 全空 / 引擎不可用 → 「无法确定时间锚点」显式报错，**绝不回退 now()**（回退是无声的口径错误）。
4. **与 dt 过滤互斥**：IR 同时给 `timeRange` 和 `field=dt` 的 filter 时显式拒绝——两套时间约束并存会让「近 7 天」与「某一天」互相打架。
5. 缓存：`TimeAnchorProvider`（Caffeine TTL 60s 按表缓存），`RecallStep` 预热一次，一次问数只查一次库。
