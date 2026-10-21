# DataPilot Ontology 语义层定义（F-01）

| 项 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 更新日期 | 2026-10-20 |
| 作者 | 张灵枫 |
| 数据源 | StarRocks `mall_dw`（4 张 ADS 内表） |
| 关联文档 | 评测集.md（F-00）、技术方案设计（待升版） |

> Ontology 是「约束 LLM 生成空间」的地基：把自然语言到字段/指标的映射、维度取值、指标口径全部定死，LLM 只允许在这个白名单空间内生成 SQL，从源头压制幻觉。

---

## 1. 定位与两层结构

| 层 | 内容 | 维护方 | 面向 |
|---|---|---|---|
| 底层：字段层（Field） | 表、字段、同义词、类型、维度取值 | 数据开发 | 技术口径 |
| 上层：指标层（Metric） | 业务指标口径、归属部门、维度 | **业务部门自助维护** | 业务口径 |

**关键解耦**：业务只关心「指标」，不关心「这个指标背后查哪张表哪个字段」。指标层由业务自助录入，字段层由开发维护，两层通过 `metric_sql` 关联。这即「指标中台 / 语义层」思想——指标口径业务定义一次，全公司问数统一。

---

## 2. 表语义（4 张 ADS）

| 表名 | 业务含义 | 主键 | 分区 |
|---|---|---|---|
| ads_trade_overview_di | 交易总览（全局日汇总） | dt | 无 |
| ads_channel_conversion_di | 渠道转化 | dt, channel | 无 |
| ads_region_gmv_di | 地域 GMV | dt, province | 无 |
| ads_product_sales_rank_di | 商品销售排行 | dt, product_id | date_trunc('day', dt) |

---

## 3. 字段层定义（Field）

### 3.1 ads_trade_overview_di

| 字段 | 类型 | 同义词 | 属性 |
|---|---|---|---|
| dt | date | 日期、时间、当天、那天 | 维度 |
| total_order_cnt | bigint | 总订单数、订单量 | 指标（可聚合） |
| paid_order_cnt | bigint | 支付订单数、已支付订单、成交订单 | 指标 |
| total_gmv | decimal | 总GMV、成交额、交易额 | 指标 |
| paid_user_cnt | bigint | 支付用户数、付费用户数、成交用户数 | 指标 |

### 3.2 ads_channel_conversion_di

| 字段 | 类型 | 同义词 | 属性 |
|---|---|---|---|
| dt | date | 日期、时间 | 维度 |
| channel | varchar | 渠道编码、渠道ID | 维度 |
| channel_name | varchar | 渠道、渠道名称 | 维度 |
| visit_uv | bigint | 访客数、UV、访问人数、曝光人数 | 指标 |
| order_cnt | bigint | 订单数、下单数 | 指标 |
| paid_order_cnt | bigint | 支付订单数、成交订单 | 指标 |
| gmv | decimal | GMV、成交额、交易额 | 指标 |
| order_conversion_rate | decimal | 下单转化率、访问转下单率、订单转化率 | 指标 |
| pay_conversion_rate | decimal | 支付转化率、下单转支付率、成交转化率 | 指标 |

### 3.3 ads_region_gmv_di

| 字段 | 类型 | 同义词 | 属性 |
|---|---|---|---|
| dt | date | 日期、时间 | 维度 |
| province | varchar | 省份编码、省份ID | 维度 |
| province_name | varchar | 省份、省份名称、地区 | 维度 |
| region | varchar | 大区、区域、片区 | 维度 |
| order_cnt | bigint | 订单数、下单数 | 指标 |
| paid_order_cnt | bigint | 支付订单数、成交订单 | 指标 |
| paid_user_cnt | bigint | 支付用户数、付费用户数 | 指标 |
| gmv | decimal | GMV、成交额、交易额 | 指标 |

### 3.4 ads_product_sales_rank_di

| 字段 | 类型 | 同义词 | 属性 |
|---|---|---|---|
| dt | date | 日期、时间 | 维度 |
| product_id | varchar | 商品ID、商品编码 | 维度 |
| product_name | varchar | 商品、商品名称、产品 | 维度 |
| category_name | varchar | 类目、品类、分类、商品类目 | 维度 |
| sale_cnt | bigint | 销量、销售件数、售卖数量、卖出数量 | 指标 |
| sale_amount | decimal | 销售额、销售金额、成交金额 | 指标 |

---

## 4. 关键歧义（字段层必须定死）

「GMV / 成交额 / 销售额」跨表对应不同字段，LLM 极易混淆，需按问题语境路由：

| 问题语境 | 正确字段 | 所在表 |
|---|---|---|
| 全局总GMV | total_gmv | ads_trade_overview_di |
| 按渠道/省份的GMV | gmv | ads_channel_conversion_di / ads_region_gmv_di |
| 按商品的销售额 | sale_amount | ads_product_sales_rank_di |

---

## 5. 维度取值（WHERE 值匹配白名单）

| 维度 | 取值 |
|---|---|
| channel_name | APP、小程序、网页 |
| region | 华东、华北、华南 |
| province_name | 江苏、上海、浙江、北京、广东 |
| category_name | 办公家具、电脑外设 |
| product_name | 机械键盘、4K显示器、人体工学椅 |

---

## 6. 指标层定义（Metric，业务部门自助维护）

### 6.1 metric_def 表结构

| 字段 | 类型 | 说明 |
|---|---|---|
| metric_id | varchar | 指标唯一 ID |
| metric_name | varchar | 指标名（如「GMV」「支付率」） |
| metric_alias | varchar | 同义词，逗号分隔（如「成交额,交易额」） |
| department | varchar | 归属部门（如「电商部」「渠道运营部」） |
| metric_sql | varchar | 口径 SQL。`simple`/`uv` 写单个聚合（如 `SUM(gmv)`）；`composite` 同表比值写 `A/B`（如 `SUM(paid_order_cnt)/SUM(visit_uv)`） |
| metric_type | varchar | `simple` 简单 / `composite` 复合（比值）/ `uv` 去重（跨期不可加） |
| table_name | varchar | 关联表（指标口径的作用表） |
| denominator_table | varchar | **Ticket 05**：跨表占比的分母表，空 = 同表比值 |
| denominator_sql | varchar | **Ticket 05**：分母口径，必须自带聚合（如 `SUM(total_gmv)`） |
| dimensions | varchar | 可用维度，逗号分隔（如「渠道,日期」） |
| owner | varchar | 指标负责人 |
| status | varchar | 启用 / 停用 |
| create_time | datetime | 创建时间 |
| update_time | datetime | 更新时间 |

### 6.2 业务自助录入流程

1. 业务在「指标管理」页填写：指标名、同义词、口径 SQL、归属部门、可用维度。
2. 落库 `metric_def`，即生效；无需改代码、无需开发介入。
3. 口径冲突时：同 `metric_name` 只允许一个启用中的定义，新增同名词需停用旧定义或走口径评审（demo 阶段简化为「同词唯一」）。

### 6.3 Agent 问数时的使用

- 召回阶段：用户问题中的业务词（GMV、支付率…）优先匹配 `metric_def.metric_alias`。
- 命中后：套用该指标的 `metric_sql` 与 `dimensions`，LLM 只负责补 WHERE 筛选条件与排序/分组，不再自己猜口径。
- 未命中指标：显式拒绝（「指标不存在或超出可查询范围」），**不回退字段层**——回退会让「Ontology 白名单约束生成空间」这条卖点失效（Ticket 05 拍板）。缺指标就补 `metric_def`，一条 PUT 即生效。

### 6.4 内置默认指标（seed 13 条，业务可覆盖）

| metric_id | metric_sql | metric_type | table_name |
|---|---|---|---|
| gmv_total | `SUM(total_gmv)` | simple | ads_trade_overview_di |
| gmv_channel | `SUM(gmv)` | simple | ads_channel_conversion_di |
| gmv_region | `SUM(gmv)` | simple | ads_region_gmv_di |
| sale_amount_product | `SUM(sale_amount)` | simple | ads_product_sales_rank_di |
| sale_cnt_product | `SUM(sale_cnt)` | simple | ads_product_sales_rank_di |
| order_cnt_total / order_cnt_channel / order_cnt_region | `SUM(total_order_cnt)` / `SUM(order_cnt)` | simple | trade / channel / region |
| paid_order_cnt | `SUM(paid_order_cnt)` | simple | trade（另挂 channel、region） |
| paid_user_cnt | `SUM(paid_user_cnt)` | uv | trade（另挂 region） |
| pay_rate | `SUM(paid_order_cnt)/SUM(total_order_cnt)` | composite | ads_trade_overview_di |
| pay_conversion_rate | `SUM(paid_order_cnt)/SUM(visit_uv)` | composite | ads_channel_conversion_di |
| gmv_channel_ratio | `SUM(gmv)`，分母 `ads_trade_overview_di` 的 `SUM(total_gmv)` | composite | ads_channel_conversion_di |

> `composite` 一律输出 `ROUND(分子/分母, 4)`；跨表分母用标量子查询，不生成 JOIN（契约见 IR_Schema.md §9）。

### 6.5 两条必须写在口径里的边界（Ticket 16 / C4·C5）

这两条不是注释洁癖，是**答案会错**的两个地方 —— 指标名看着都对，数却是另一个东西：

| 指标 | 边界 | 为什么必须写进口径 | 用户问到时怎么答 |
|---|---|---|---|
| `sale_amount_product` 商品销售额 | **含未支付明细** | 来源 `ads_product_sales_rank_di.sale_amount` 是全量明细求和，退款/未支付分支可能仍在里面。它和 `gmv_total`（成交口径）不是同一件事，同名混用会让「商品销售额之和」与「总 GMV」对不上 | 答商品销售额时明确「含未支付」，不接受「商品销售额占比 = 该商品销售额 / 总GMV」这类跨口径比值 |
| `paid_user_cnt` 支付用户数 | **跨期不可加** | `metric_type=uv`，是当天去重人数。`SUM` 多天会把同一个人重复计数（7 天累计支付用户 ≠ 7 天各自支付用户之和） | 「近 7 天支付用户数」只能答「按天趋势 / 取某一天」，不能直接给一个 SUM 出来的累计值；需要累计时必须重算 `COUNT(DISTINCT user_id)`（当前 ADS 层没有用户明细，因此**不支持**，要显式拒绝而不是给个偏大的数） |

> 落地位置分两件事，别混（QA-16 P2-1 就是混在这里）：
> - **口径说明本身**（表格里这两句）：`deploy/seed.sql` 第 3 节指标口径的注释与 `field_metadata` 的 `field_comment`。改口径只改这两处，**不用动代码**。
> - **C5② 的「显式拒绝」**：由 `agent/step/RewriteStep#unsupportedScope` 在生成 IR 之前判定（Ticket 17 落地），拒绝文案带口径原因与两条可执行替代问法。**这一处要改代码** —— 新增去重人数类指标或补别名时，必须同步 `UNIQUE_COUNT_METRICS` 词表，否则用户按新别名问「近 N 天累计」会退回「无法识别查询意图」。

---

## 7. 与后续环节的关系

- **F-02 IR Schema**：把「召回到的字段/指标/维度/筛选」组织成中间表示，供 Prompt 模板消费。
- **F-04 闸门**：存在性闸门校验字段/指标是否落在本 Ontology 白名单；代价闸门用指标口径判断 SQL 复杂度。
- **F-06 LLM**：本 Ontology 全量注入 system prompt，约束生成空间。
