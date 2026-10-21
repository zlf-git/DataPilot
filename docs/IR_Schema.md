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

> 与代码 `com.zlf.datapilot.ir.QueryIr` 逐字段对齐（Ticket 03/04/05 回填；此前 §2 只写了 01 期的 6 个字段，与末尾契约表打架）。

```java
public class QueryIr {
    String question;              // 原始自然语言，仅审计/日志用，不进 SQL
    SelectMode selectMode;        // AGG（聚合，metrics 必填）/ DETAIL（明细 DISTINCT，metrics 必须为空）；空时按 metrics 推断
    List<MetricRef> metrics;      // 指标（AGG 必填；DETAIL 必须为空）
    List<DimensionRef> dimensions;// 维度（AGG 走 GROUP BY；DETAIL 是 SELECT DISTINCT 的列，≥1）
    List<Filter> filters;         // 筛选条件（可空，value 全部参数化绑定）
    List<OrderBy> orderBy;        // 排序（可空，field 可为 metricId 别名或字段名）
    Integer limit;                // TopN（可空，空时注入 gate_config.max_rows）
    RatioRef ratio;               // 【已停用】Ticket 05 起占比走 composite 指标，生成器遇到即显式拒绝
    TimeRange timeRange;          // 相对时间窗口（Ticket 04），展开半开区间 dt > ? AND dt <= ?
}
```

### 2.1 MetricRef（指标引用）

```java
public class MetricRef {
    String metricId;   // 指标 ID，必须落 Ontology 指标白名单（metric_def.metric_id）
}
```

- `metricId` 命中 `metric_def` 后，口径 SQL（`metric_sql`）与可用维度（`dimensions`）由服务端派生，**不信任 LLM 传入的聚合/字段**。
- 指标未命中白名单 → 存在性闸门直接拒绝。**不做字段层回退**（Ticket 05 拍板：回退会弱化「Ontology 白名单」卖点，Q8 已由 `sale_cnt_product` 指标解决）。

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

### 示例一（对应评测集 Q6：单表分组 + 复合指标 + 排序 + 筛选）

问题：「10月19日各渠道的支付转化率分别是多少？按从高到低排序」

```json
{
  "question": "10月19日各渠道的支付转化率分别是多少？按从高到低排序",
  "selectMode": "AGG",
  "metrics": [
    { "metricId": "pay_conversion_rate" }
  ],
  "dimensions": [
    { "field": "channel_name" }
  ],
  "filters": [
    { "field": "dt", "op": "EQ", "value": "2026-10-19" }
  ],
  "orderBy": [
    { "field": "pay_conversion_rate", "direction": "desc" },
    { "field": "channel_name", "direction": "asc" }
  ],
  "limit": null
}
```

### 示例二（对应评测集 Q15：跨表占比，走 composite 指标）

问题：「10月19日各渠道GMV占当天总GMV的比例分别是多少？」

```json
{
  "question": "10月19日各渠道GMV占当天总GMV的比例分别是多少？",
  "selectMode": "AGG",
  "metrics": [
    { "metricId": "gmv_channel_ratio" }
  ],
  "dimensions": [
    { "field": "channel_name" }
  ],
  "filters": [
    { "field": "dt", "op": "EQ", "value": "2026-10-19" }
  ],
  "orderBy": [
    { "field": "gmv_channel_ratio", "direction": "desc" }
  ],
  "limit": null
}
```

> 跨表信息（分母表 `ads_trade_overview_di`、分母口径 `SUM(total_gmv)`）**不进 IR**，全部来自 `metric_def`。IR 只声明「用哪个指标」，表与口径都由服务端派生——这是白名单约束生成空间的核心。

### 示例三（对应评测集 Q14：时间窗口 + TopN）

```json
{
  "question": "近7天里总GMV最高的是哪一天？",
  "selectMode": "AGG",
  "metrics": [ { "metricId": "gmv_total" } ],
  "dimensions": [ { "field": "dt" } ],
  "orderBy": [ { "field": "gmv_total", "direction": "desc" } ],
  "limit": 1,
  "timeRange": { "relativeWindow": "LAST_N_DAYS", "days": 7 }
}
```

---

## 4. 安全边界（IR 与白名单绑定）

| 校验点 | 规则 | 违例处理 |
|---|---|---|
| 表名 | IR 不携带表，表由 `metric_def.table_name` / `dimension_def` 派生，且必须在表白名单（4 张 ADS） | 拒绝执行 |
| 字段名 | 维度/过滤/排序字段必须落字段白名单，**且必须属于该指标的表**（`table.field` 复合键） | 拒绝执行 |
| 指标名 | 每个 `metricId` 必须落指标白名单 `metric_def` | 直接拒绝（**不回退字段层**，Ticket 05 拍板） |
| 维度属性 | 维度字段必须能在 `dimension_def` 反查到表 | 拒绝执行 |
| 聚合 | 聚合由服务端按指标口径派生，不采信 LLM | 覆盖 LLM 传值 |
| 身份 | 不进 IR，走传输层 token 绑定 | — |

---

## 5. IR 与五道闸门的映射

| 闸门 | 作用层 | 说明 |
|---|---|---|
| ① 存在性 | IR 层 | 指标/字段/（占比）分母表落白名单；同一指标的表归属校验见 §4 |
| ② 权限 | IR 层 | 主表与占比分母表都必须在只读表白名单，执行走只读账号 |
| ③ 代价 | IR 层 | 预估复杂度 = 指标数 × 维度数 × 表 join 数，超阈值拒绝 |
| ④ 语法 | SQL 层 | IR→SQL 后，JSqlParser 解析校验语法 + 表/字段二次比对 |
| ⑤ 结果自检 | 结果层 | 执行后空结果/超长结果包装（不拒绝，给提示） |

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
| `ratio` | `{numerator, denominator}` | 否 | **已停用（Ticket 05）**：占比改由 `metric_type=composite` 的指标承载，生成器遇到 `ratio` 直接拒绝并提示改用指标——同一语义不允许两套执行路径 |

补充契约：

1. `orderBy.field` 取值域 = **metricId ∪ 字段名**。先按 metricId 解析成 SELECT 别名（`ORDER BY gmv_channel DESC`），解析不到再当字段名；两者都不在允许集（AGG=分组维度∪指标别名，DETAIL=DISTINCT 列）时显式报错。
2. `limit` 为空时由生成器注入 `gate_config.max_rows`（默认 1000），不是无限返回。
3. DETAIL 的表由 `dimension_def` 的 field→table 反查：多个维度/过滤字段取候选表交集，交集为空报「没有共同所属的表」，多于一张报「候选表 [...]，请补充更具体的维度」——**不猜表**。
4. `FilterOp` 只保留 `EQ/IN/LIKE/GT/GE/LT/LE`，BETWEEN 已删（闭区间与 04 的半开区间冲突）。参数个数：EQ/GT/GE/LT/LE 各 1 个，IN 按元素数展开，LIKE 绑 `%value%`。

---

## 8. Ticket 04 新增字段：timeRange（时间窗口）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `timeRange.relativeWindow` | `LAST_N_DAYS`（兼容 `LAST_DAYS`/`RECENT_DAYS`） | **是**（有 timeRange 时） | 相对窗口类型。缺失或空白**显式拒绝**，不按 `LAST_N_DAYS` 兜底（QA-04 P2-1：兜底等于替用户决定口径，与注释「不做猜测」矛盾） |
| `timeRange.days` | int | **是**（有 timeRange 时） | 窗口天数（含锚点当天），1~366；越界显式拒绝 |
| `timeRange.anchor` | `yyyy-MM-dd` | 否 | **解析结果而非模型输入**：由数据 `MAX(dt)` 算出后回填；模型若给了值，先**格式归一**再比对，与数据最新日不一致直接拒绝 |

生成契约（**全文禁 BETWEEN**）：

```sql
-- timeRange{relativeWindow:"LAST_N_DAYS", days:7} + anchor=2026-10-19
WHERE dt > ? AND dt <= ?        -- 参数：['2026-10-12', '2026-10-19']
```

1. **为什么不用 BETWEEN**：`BETWEEN '10-12' AND '10-19'` 是闭区间，会取 10-12~10-19 共 **8** 天，与「近 7 天」不符（审计 P0#1）。半开下界 + 闭合上界才是 7 天。
2. **为什么锚点取 `MAX(dt)` 而不是 `now()`**：数据滞后一天时 `now()` 会把窗口整体前移，最后一天落在空分区，GMV 凭空少一天。锚点锚在数据上，口径才稳定可复现。
3. **锚点失败即拒绝**：表无 `dt` 列 / `dt` 全空 / 引擎不可用 → 「无法确定时间锚点」显式报错，**绝不回退 now()**（回退是无声的口径错误）。
4. **与 dt 过滤互斥**：IR 同时给 `timeRange` 和 `field=dt` 的 filter 时显式拒绝——两套时间约束并存会让「近 7 天」与「某一天」互相打架。
5. **缓存**：`TimeAnchorProvider`（Caffeine **按表原子读** `get(key, loader)`，TTL 由 `datapilot.engine.anchor-ttl-seconds` 配置，默认 60s），`RecallStep` 预热一次。准确表述是**「每张表每 TTL 最多查一次库」**，不是「一次问数只查一次库」——首次问数会为召回出的每张候选表各查一次（QA-04 用 TCP 计数实测：冷启动 5 条连接 = 1 执行 + 4 预热）。
6. **anchor 格式归一**（QA-04 P2-4）：`20261019` / `2026/10/19` / `"2026-10-19 00:00:00"` 统一成 `2026-10-19` 再比对；真正无法解析的（`19/10/2026`）报「格式非法」，不再冒充「与数据不一致」——后者会被误读成数据出问题。

### 8.1 窗口题的评测硬规则（QA-04 P1-2）

**值对拍在这批数据上判不出半开/闭区间**：7 天窗口的下界 `2026-10-12` 在四张 ADS 表里都是空分区，所以 `BETWEEN '10-12' AND '10-19'`（8 天）与 `dt > '10-12' AND dt <= '10-19'`（7 天）**逐题返回值完全相同**（Q2 都是 69，Q11 都是 138821.73……）。哪天有人把 `buildTimeRange` 改回 BETWEEN，只做行级对拍的评测会全部放行。

因此：

1. **窗口题必须断言 SQL 形状**（强制）：SQL 里出现 `BETWEEN` → 直接 FAIL；必须同时含 `dt > ?` 与 `dt <= ?`；参数必须恰为 `[anchor-days, anchor]` 且锚点 == 数据 `MAX(dt)`。`qa/e2e_stub.py` 的 `window_check` 与 `sqlMust` 都按此判定。
2. **必须保留一条 days=3 的值级探针**：`stub_ir/probe_window3.json`（`gmv_total` + `LAST_N_DAYS 3`）。锚点 2026-10-19 下半开取 10-17/18/19 = **157702.85**；闭区间会多吃 10-16 的 66184.50 = **223887.35**。只有「等于 157702.85 且不等于 223887.35」才能判出窗口是半开的。
3. 新增窗口题时，若所选天数在下界外一天恰好无数据，**该题不能作为值级判据**，必须在评测集里标注「仅形状可判」。

---

## 9. Ticket 05：复合指标（composite）与跨表占比

IR 本身<b>不新增字段</b>：复合指标由 `metric_def` 声明，IR 只写 `metricId`。全部口径信息留在元数据层，LLM 无从拼出分子分母。

| metric_def 列 | 含义 |
|---|---|
| `metric_type` | `simple` / `composite` / `uv` |
| `metric_sql` | 同表比值时写 `A/B`；跨表比值时只写分子（如 `SUM(gmv)`） |
| `denominator_table` | 跨表占比的分母表；空 = 同表比值 |
| `denominator_sql` | 分母口径，**必须自带聚合**（如 `SUM(total_gmv)`） |

### 9.1 生成契约

```sql
-- 同表比值（pay_rate / pay_conversion_rate）：ROUND 只保留一列
SELECT dims..., ROUND(SUM(paid_order_cnt) / NULLIF(SUM(visit_uv), 0), 4) AS pay_conversion_rate
FROM ads_channel_conversion_di WHERE dt = ? GROUP BY dims

-- 跨表占比（gmv_channel_ratio）：分母走标量子查询，不生成 JOIN
SELECT channel_name, ROUND(SUM(gmv) / NULLIF((SELECT SUM(total_gmv) FROM ads_trade_overview_di WHERE dt = ?), 0), 4) AS gmv_channel_ratio
FROM ads_channel_conversion_di WHERE dt = ? GROUP BY channel_name
```

1. **为什么跨表用标量子查询而不是 JOIN**：Q15 是「渠道表 / 总览表」，JOIN 要求两表按 dt 对齐，分母侧一旦一对多就会放大分子行数；标量子查询是标量常量，不改变分组粒度。两种写法结果等价，对拍只比结果不比 SQL 文本。
2. **分母必须带聚合**（审计 P1#14）：漏了 `SUM` 的标量子查询会返回多行，SQL 直接报错。
3. **参数顺序**（审计 P1#14）：子查询文本在主查询 WHERE 之前，所以**分母参数在前、主查询参数在后**，由生成器按「分母 → 主查询」顺序压参数。实测 Q15 params = `['2026-10-19', '2026-10-19']`。
4. **分母只继承时间条件**（`timeRange` 或 `dt` 过滤）。非时间过滤叠加在跨表占比上时**显式拒绝**：把渠道过滤推到分母会变成「自己占自己」恒等于 1，而这个口径没有唯一答案，不猜也不静默丢。
5. **除零保护**：分母套 `NULLIF(...,0)`，分母为 0 得 NULL（占比未知），不是除零异常把链路打断。
6. **列别名取 metricId**（`gmv_channel_ratio`），不写死 `ratio`：Q6/Q9 同为 composite，别名规则必须统一，否则同一机制出现两套列名，前端/报告无法按 metricId 定位列。
7. **多指标仍不支持**：一条 IR 只允许一个指标（`metrics.size()>1` 显式拒绝）。复合指标的分子分母来自元数据，不是「IR 里的两个指标」。

## 10. QA-03 反馈回填：标识符大小写、IN 取值、结果顺序

三处都遵循同一条原则：**能确定的归一，不能确定的显式拒绝**，绝不静默改写语义。

### 10.1 字段/指标标识符大小写不敏感

| 环节 | 行为 |
|---|---|
| 白名单校验（存在性闸门） | 字段名、metricId 一律先 `trim().toLowerCase()` 再比白名单（白名单真值全小写） |
| 生成 SQL | 用**白名单真值（小写）**出文本，不把 LLM 给的大写标识符带进 SQL |
| 效果 | IR 写 `Channel_Name` / `DT` 不再被判「字段不存在」，生成 `channel_name` / `dt` |

只归一小写，不归一到别的名字：`channel` 和 `channel_name` 是两个不同字段，改写名字等于篡改语义。

### 10.2 IN 的多值只认 JSON 数组

| IR 形态 | 结果 |
|---|---|
| `"value": ["APP", "小程序"]` | ✅ `channel_name IN (?, ?)`，2 个参数 |
| `"value": "APP"` | ✅ 单值 `IN (?)` |
| `"value": "APP,小程序"` | ❌ 显式拒绝：「IN 过滤值含逗号，字符串不再按逗号切分」 |
| `"value": []` | ❌ 显式拒绝：「IN 过滤值不能为空」 |

字符串硬切逗号的问题：值本身可能是 `"上海,浙江"` 这种含逗号的单个值，切完语义就变了，而且看不出来。**多值一律用数组**，Prompt 同步约束。

### 10.3 结果顺序确定化（tie-breaker）

| 场景 | 生成规则 |
|---|---|
| AGG 有 dimensions、无 orderBy | `ORDER BY <维度...> ASC` |
| AGG 有 orderBy | `ORDER BY <显式排序...>, <其余维度...> ASC`（已出现的维度不重复） |
| DETAIL（`SELECT DISTINCT`） | 同理，按 SELECT 列升序兜底 |

原因：DISTINCT 与并列排序（Q6 两个 1.0）下引擎返回行序不保证稳定，同一 SQL 两轮跑出不同顺序，06b 的对拍会随机失败。升序 tie-breaker 不改变任何一行的取值，只在并列/无序时给出唯一顺序。

**注意**：顺序稳定 ≠ 可以用顺序对拍。06b 仍以**集合比对**为准（行序依赖数据库排序规则，换引擎/换字符集就可能变）；这里只是让「同一环境下两轮结果一致」成为可断言的事实。
