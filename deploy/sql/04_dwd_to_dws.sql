-- ============================================================
-- 作业4：Paimon DWD → Paimon DWS（湖留存：3 张日汇总表，聚合）
-- 链路：dwd_orders → dws_channel_order_day / dws_province_order_day / dws_product_sales_day
-- ============================================================

SET 'execution.checkpointing.interval' = '10 s';
SET 'execution.checkpointing.mode' = 'EXACTLY_ONCE';
SET 'table.exec.sink.upsert-materialize' = 'NONE';
SET 'parallelism.default' = '1';

CREATE CATALOG IF NOT EXISTS paimon_catalog WITH (
    'type' = 'paimon',
    'warehouse' = 'hdfs://hadoop-namenode:9000/paimon_warehouse',
    'metastore' = 'hive',
    'uri' = 'thrift://hive-metastore:9083'
);

USE CATALOG paimon_catalog;
USE mall_dw;

-- 1. DWS 渠道日汇总
CREATE TABLE IF NOT EXISTS dws_channel_order_day (
    dt              STRING,
    channel         STRING,
    uv              BIGINT,
    order_cnt       BIGINT,
    paid_order_cnt  BIGINT,
    gmv             DECIMAL(16,2),
    PRIMARY KEY (dt, channel) NOT ENFORCED
) PARTITIONED BY (dt) WITH ('bucket' = '1');

-- 2. DWS 省份日汇总
CREATE TABLE IF NOT EXISTS dws_province_order_day (
    dt              STRING,
    province        STRING,
    order_cnt       BIGINT,
    paid_order_cnt  BIGINT,
    paid_user_cnt   BIGINT,
    gmv             DECIMAL(16,2),
    PRIMARY KEY (dt, province) NOT ENFORCED
) PARTITIONED BY (dt) WITH ('bucket' = '1');

-- 3. DWS 商品日汇总
CREATE TABLE IF NOT EXISTS dws_product_sales_day (
    dt              STRING,
    product_id      STRING,
    product_name    STRING,
    category_name   STRING,
    sale_cnt        BIGINT,
    sale_amount     DECIMAL(16,2),
    PRIMARY KEY (dt, product_id) NOT ENFORCED
) PARTITIONED BY (dt) WITH ('bucket' = '1');

-- 4. 写入 DWS 渠道汇总
INSERT INTO dws_channel_order_day
SELECT
    dt,
    channel,
    COUNT(DISTINCT buyer_uid)                                   AS uv,
    COUNT(*)                                                    AS order_cnt,
    SUM(CASE WHEN pay_status = 'PAID' THEN 1 ELSE 0 END)        AS paid_order_cnt,
    SUM(CASE WHEN pay_status = 'PAID' THEN total_amount ELSE 0 END) AS gmv
FROM dwd_orders
GROUP BY dt, channel;

-- 5. 写入 DWS 省份汇总
INSERT INTO dws_province_order_day
SELECT
    dt,
    province,
    COUNT(*)                                                    AS order_cnt,
    SUM(CASE WHEN pay_status = 'PAID' THEN 1 ELSE 0 END)        AS paid_order_cnt,
    COUNT(DISTINCT CASE WHEN pay_status = 'PAID' THEN buyer_uid END) AS paid_user_cnt,
    SUM(CASE WHEN pay_status = 'PAID' THEN total_amount ELSE 0 END) AS gmv
FROM dwd_orders
GROUP BY dt, province;

-- 6. 写入 DWS 商品汇总
INSERT INTO dws_product_sales_day
SELECT
    dt,
    product_id,
    MAX(product_name)                                           AS product_name,
    MAX(category_name)                                          AS category_name,
    COUNT(*)                                                    AS sale_cnt,
    SUM(total_amount)                                           AS sale_amount
FROM dwd_orders
GROUP BY dt, product_id;
