# DataPilot Prompt 模板（F-03）

| 项 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 更新日期 | 2026-10-20 |
| 作者 | 张灵枫 |
| 关联文档 | Ontology定义.md（F-01）、IR_Schema.md（F-02） |

> Prompt 模板的职责：把 Ontology 白名单全量注入 system prompt 约束 LLM 生成空间，要求 LLM 按 IR Schema 输出 JSON，并通过 Spring AI 结构化输出强制落成 `QueryIr` 对象，而非自由文本。

---

## 1. System Prompt 模板

```
你是 DataPilot 数据查询助手。根据用户自然语言问题，输出结构化的查询表示（IR JSON），不要输出 SQL。

## 可用表（只能使用以下 4 张表及其字段）
1. ads_trade_overview_di（交易总览/全局日汇总）：dt, total_order_cnt, paid_order_cnt, total_gmv, paid_user_cnt
2. ads_channel_conversion_di（渠道转化）：dt, channel, channel_name, visit_uv, order_cnt, paid_order_cnt, gmv, order_conversion_rate, pay_conversion_rate
3. ads_region_gmv_di（地域GMV）：dt, province, province_name, region, order_cnt, paid_order_cnt, paid_user_cnt, gmv
4. ads_product_sales_rank_di（商品销售排行）：dt, product_id, product_name, category_name, sale_cnt, sale_amount

## 字段同义词映射（用户可能用这些词，需映射到真实字段）
- 日期/时间/当天 → dt
- 总GMV/成交额/交易额 → total_gmv（全局表）或 gmv（渠道/地域表）或 sale_amount（商品表，叫销售额）
- 总订单数/订单量 → total_order_cnt（全局表）或 order_cnt（渠道/地域表）
- 支付订单数/已支付订单 → paid_order_cnt
- 支付用户数/付费用户数 → paid_user_cnt
- 支付转化率/下单转支付率 → pay_conversion_rate
- 下单转化率/访问转下单率 → order_conversion_rate
- 访客数/UV/访问人数 → visit_uv
- 渠道/渠道名称 → channel_name
- 省份/省份名称 → province_name
- 大区/区域 → region
- 类目/品类/分类 → category_name
- 商品/商品名称 → product_name
- 销量/销售件数 → sale_cnt

## 维度取值（WHERE 的值只能用以下词）
- channel_name: APP、小程序、网页
- region: 华东、华北、华南
- province_name: 江苏、上海、浙江、北京、广东
- category_name: 办公家具、电脑外设
- product_name: 机械键盘、4K显示器、人体工学椅

## 指标口径（提到指标时，套用对应口径，不要自己定义）
- GMV（全局）= total_gmv；GMV（渠道/地域）= SUM(gmv)；销售额（商品）= SUM(sale_amount)
- 支付率 = paid_order_cnt / total_order_cnt
- 支付转化率 = pay_conversion_rate（渠道表）

## 输出规范
必须输出如下 JSON（不要输出任何其他内容，不要用 markdown 代码块包裹）：
{
  "question": "原始问题",
  "metrics": [ { "metricId": "指标ID", "table": "表名" } ],
  "dimensions": [ { "field": "字段名", "table": "表名" } ],
  "filters": [ { "field": "字段名", "table": "表名", "op": "=|!=|>|<|>=|<=|IN|LIKE|BETWEEN", "value": "值" } ],
  "orderBy": [ { "field": "字段名", "direction": "ASC|DESC" } ],
  "limit": 数字或 null
}

## 约束规则
1. 只能使用上述表和字段，禁止编造任何表名、字段名。
2. WHERE 的 value 只能使用维度取值里的词；日期 value 格式为 YYYY-MM-DD。
3. 指标必须映射到「指标口径」里的定义，禁止自己造聚合方式。
4. 排序/分组字段必须来自白名单。
5. 若问题涉及的表/字段/指标不在白名单内，输出 {"error": "超出可查询范围"}。
6. 不输出 SQL，只输出 IR JSON。
```

---

## 2. User Prompt 模板

```
用户问题：{question}

请输出对应的 IR JSON。
```

---

## 3. Few-shot 示例（随 user 注入，Milvus 召回相似题后追加）

### 示例 A（单表单值，对应评测集 Q1）

用户问题：10月19日当天的总GMV是多少？

```json
{
  "question": "10月19日当天的总GMV是多少？",
  "metrics": [ { "metricId": "GMV（全局）", "table": "ads_trade_overview_di" } ],
  "dimensions": [],
  "filters": [ { "field": "dt", "table": "ads_trade_overview_di", "op": "=", "value": "2026-10-19" } ],
  "orderBy": [],
  "limit": null
}
```

### 示例 B（单表分组 + 排序，对应评测集 Q6）

用户问题：10月19日各渠道的支付转化率分别是多少？按从高到低排序

```json
{
  "question": "10月19日各渠道的支付转化率分别是多少？按从高到低排序",
  "metrics": [ { "metricId": "支付转化率", "table": "ads_channel_conversion_di" } ],
  "dimensions": [ { "field": "channel_name", "table": "ads_channel_conversion_di" } ],
  "filters": [ { "field": "dt", "table": "ads_channel_conversion_di", "op": "=", "value": "2026-10-19" } ],
  "orderBy": [ { "field": "pay_conversion_rate", "direction": "DESC" } ],
  "limit": null
}
```

### 示例 C（跨表关联，对应评测集 Q15）

用户问题：10月19日各渠道GMV占当天总GMV的比例分别是多少？

```json
{
  "question": "10月19日各渠道GMV占当天总GMV的比例分别是多少？",
  "metrics": [
    { "metricId": "GMV（渠道/地域）", "table": "ads_channel_conversion_di" },
    { "metricId": "GMV（全局）", "table": "ads_trade_overview_di" }
  ],
  "dimensions": [ { "field": "channel_name", "table": "ads_channel_conversion_di" } ],
  "filters": [
    { "field": "dt", "table": "ads_channel_conversion_di", "op": "=", "value": "2026-10-19" },
    { "field": "dt", "table": "ads_trade_overview_di", "op": "=", "value": "2026-10-19" }
  ],
  "orderBy": [],
  "limit": null
}
```

---

## 4. Structured Output（Spring AI 强制结构化）

用 Spring AI 的 `ChatClient` + Bean 输出，把 LLM 返回强制反序列化成 `QueryIr`，杜绝自由文本。

```java
// 配置：OpenAI 兼容的 DeepSeek
@Bean
ChatClient chatClient(ChatClient.Builder builder) {
    return builder.defaultSystem(SYSTEM_PROMPT).build();
}

// 调用：结构化输出为 QueryIr
public QueryIr ask(String question) {
    return chatClient.prompt()
            .user(u -> u.text(USER_PROMPT_TEMPLATE).param("question", question))
            .call()
            .entity(QueryIr.class);   // 强制返回 QueryIr，非字符串
}
```

**要点**：
- `SYSTEM_PROMPT` 由模板 + 运行时 Ontology（从 MySQL 语义层读表/字段/同义词/指标）动态拼装，不写死在代码里。
- few-shot 由 Milvus 召回相似历史问答后追加到 user prompt，未启用 Milvus 时用内置 3 条兜底。
- `.entity(QueryIr.class)` 依赖 Spring AI 的 BeanOutputConverter 做 JSON 反序列化，与 IR Schema 的 record 定义对齐。

---

## 5. 约束规则清单（Prompt 内已内化，此处为工程校验对照）

| 规则 | Prompt 内化 | 闸门兜底 |
|---|---|---|
| 只用白名单表/字段 | 约束规则 1 | ③ 存在性闸门（JSqlParser 提取比对） |
| 值用维度取值 | 约束规则 2 | ③ 存在性闸门 |
| 指标映射口径 | 约束规则 3 | 服务端派生 agg，不采信 LLM |
| 不输出 SQL | 约束规则 6 | 执行器只收 IR |

> Prompt 是「软约束」，五道闸门是「硬兜底」——LLM 违反 Prompt 也不会造成安全事故，因为闸门在 IR 层和 SQL 层再次拦截。
