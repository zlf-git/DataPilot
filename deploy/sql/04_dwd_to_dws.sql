-- ============================================================
-- 作业4：Paimon DWD → Paimon DWS（湖留存：4 张日汇总，流式持续维护）
-- 链路：dwd_orders → dws_channel_order_day / dws_province_order_day /
--       dws_product_sales_day / dws_trade_overview_day（新增全局日汇总）
-- 关键设计：
--   1. 维表 join：事实表只存外键，此处用 lookup join（FOR SYSTEM_TIME AS OF）
--      把 dim_* 维度描述拼回 DWS，供 ADS 直接使用。
--   2. State TTL：流式无界 GROUP BY dt 的状态随 dt 增长，设 table.exec.state.ttl
--      兜底——超过 48h 未更新的 dt 状态自动清理，防状态无限膨胀。
--      （副作用：迟到/超时更新的订单会重新从 0 累计，故 TTL 需 ≥ 订单状态
--       变更容忍窗口，2~7 天为合理区间。）
--   3. trade_overview 下沉：经营总览全局日汇总在此流式维护，job5 只从 DWS 读，
--      不再扫 dwd_orders 明细全表（解决 T+1 全量重算越跑越慢问题）。
-- ============================================================

SET 'execution.checkpointing.interval' = '10 s';
SET 'execution.checkpointing.mode' = 'EXACTLY_ONCE';
SET 'execution.restart-strategy' = 'fixed-delay';
SET 'execution.restart-strategy.fixed-delay.attempts' = '10';
SET 'execution.restart-strategy.fixed-delay.delay' = '10s';
SET 'table.exec.sink.upsert-materialize' = 'NONE';
SET 'parallelism.default' = '1';
-- 状态 TTL：日汇总 GROUP BY 状态按「访问时间」过期，防状态随 dt 无界膨胀
SET 'table.exec.state.ttl' = '48h';

CREATE CATALOG IF NOT EXISTS paimon_catalog WITH (
    'type' = 'paimon',
    'warehouse' = 'hdfs://hadoop-namenode:9000/paimon_warehouse',
    'metastore' = 'hive',
    'uri' = 'thrift://hive-metastore:9083'
);

USE CATALOG paimon_catalog;
USE mall_dw;

-- ==================== 1. DWS 表（4 张，含维表描述字段） ====================

-- 1.1 渠道日汇总（channel_name 来自 dim_channel）
CREATE TABLE IF NOT EXISTS dws_channel_order_day (
    dt              STRING,
    channel         STRING,
    channel_name    STRING,
    uv              BIGINT,
    order_cnt       BIGINT,
    paid_order_cnt  BIGINT,
    gmv             DECIMAL(16,2),
    PRIMARY KEY (dt, channel) NOT ENFORCED
) PARTITIONED BY (dt) WITH ('changelog-producer' = 'input', 'bucket' = '1');

-- 1.2 省份日汇总（province_name / region 来自 dim_province）
CREATE TABLE IF NOT EXISTS dws_province_order_day (
    dt              STRING,
    province        STRING,
    province_name   STRING,
    region          STRING,
    order_cnt       BIGINT,
    paid_order_cnt  BIGINT,
    paid_user_cnt   BIGINT,
    gmv             DECIMAL(16,2),
    PRIMARY KEY (dt, province) NOT ENFORCED
) PARTITIONED BY (dt) WITH ('changelog-producer' = 'input', 'bucket' = '1');

-- 1.3 商品日汇总（product_name / category_name 来自 dim_product）
CREATE TABLE IF NOT EXISTS dws_product_sales_day (
    dt              STRING,
    product_id      STRING,
    product_name    STRING,
    category_name   STRING,
    sale_cnt        BIGINT,
    sale_amount     DECIMAL(16,2),
    PRIMARY KEY (dt, product_id) NOT ENFORCED
) PARTITIONED BY (dt) WITH ('changelog-producer' = 'input', 'bucket' = '1');

-- 1.4 经营总览日汇总（全局无维度，biz_type 常量键兜底分区主键约束）
CREATE TABLE IF NOT EXISTS dws_trade_overview_day (
    dt               STRING,
    biz_type         STRING,
    total_order_cnt  BIGINT,
    paid_order_cnt   BIGINT,
    total_gmv        DECIMAL(16,2),
    paid_user_cnt    BIGINT,
    PRIMARY KEY (dt, biz_type) NOT ENFORCED
) PARTITIONED BY (dt) WITH ('changelog-producer' = 'input', 'bucket' = '1');

-- ==================== 2. 写入 DWS（lookup join 维表拼描述） ====================

-- 2.1 渠道汇总：join dim_channel 拿 channel_name
INSERT INTO dws_channel_order_day
SELECT
    o.dt,
    o.channel,
    c.channel_name,
    COUNT(DISTINCT o.buyer_uid)                                   AS uv,
    COUNT(*)                                                      AS order_cnt,
    SUM(CASE WHEN o.pay_status = 'PAID' THEN 1 ELSE 0 END)        AS paid_order_cnt,
    SUM(CASE WHEN o.pay_status = 'PAID' THEN o.total_amount ELSE 0 END) AS gmv
FROM (SELECT *, PROCTIME() AS proc_time FROM dwd_orders) o
JOIN dim_channel FOR SYSTEM_TIME AS OF o.proc_time AS c
  ON o.channel = c.channel_code
GROUP BY o.dt, o.channel, c.channel_name;

-- 2.2 省份汇总：join dim_province 拿 province_name / region
INSERT INTO dws_province_order_day
SELECT
    o.dt,
    o.province,
    p.province_name,
    p.region,
    COUNT(*)                                                      AS order_cnt,
    SUM(CASE WHEN o.pay_status = 'PAID' THEN 1 ELSE 0 END)        AS paid_order_cnt,
    COUNT(DISTINCT CASE WHEN o.pay_status = 'PAID' THEN o.buyer_uid END) AS paid_user_cnt,
    SUM(CASE WHEN o.pay_status = 'PAID' THEN o.total_amount ELSE 0 END) AS gmv
FROM (SELECT *, PROCTIME() AS proc_time FROM dwd_orders) o
JOIN dim_province FOR SYSTEM_TIME AS OF o.proc_time AS p
  ON o.province = p.province_code
GROUP BY o.dt, o.province, p.province_name, p.region;

-- 2.3 商品汇总：join dim_product 拿 product_name / category_name
INSERT INTO dws_product_sales_day
SELECT
    o.dt,
    o.product_id,
    dp.product_name,
    dp.category_name,
    COUNT(*)                 AS sale_cnt,
    SUM(o.total_amount)      AS sale_amount
FROM (SELECT *, PROCTIME() AS proc_time FROM dwd_orders) o
JOIN dim_product FOR SYSTEM_TIME AS OF o.proc_time AS dp
  ON o.product_id = dp.product_id
GROUP BY o.dt, o.product_id, dp.product_name, dp.category_name;

-- 2.4 经营总览：全局日汇总（无维度，biz_type 恒 'TRADE' 兜底）
INSERT INTO dws_trade_overview_day
SELECT
    dt,
    'TRADE'                                                          AS biz_type,
    COUNT(*)                                                         AS total_order_cnt,
    SUM(CASE WHEN pay_status = 'PAID' THEN 1 ELSE 0 END)             AS paid_order_cnt,
    SUM(CASE WHEN pay_status = 'PAID' THEN total_amount ELSE 0 END)  AS total_gmv,
    COUNT(DISTINCT CASE WHEN pay_status = 'PAID' THEN buyer_uid END) AS paid_user_cnt
FROM dwd_orders
GROUP BY dt;
