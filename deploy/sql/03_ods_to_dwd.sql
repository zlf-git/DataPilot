-- ============================================================
-- 作业3：Paimon ODS → Paimon DWD（湖留存：明细层清洗 + dt 字段）
-- 链路：ods_orders → dwd_orders
-- ============================================================

SET 'execution.checkpointing.interval' = '10 s';
SET 'execution.checkpointing.mode' = 'EXACTLY_ONCE';
SET 'table.exec.sink.upsert-materialize' = 'NONE';

CREATE CATALOG IF NOT EXISTS paimon_catalog WITH (
    'type' = 'paimon',
    'warehouse' = 'hdfs://hadoop-namenode:9000/paimon_warehouse',
    'metastore' = 'hive',
    'uri' = 'thrift://hive-metastore:9083'
);

USE CATALOG paimon_catalog;
USE mall_dw;

-- 1. DWD 订单明细表（主键 order_id，含标准化 dt 字段）
CREATE TABLE IF NOT EXISTS dwd_orders (
    order_id        STRING,
    platform_code   STRING,
    shop_id         STRING,
    buyer_uid       STRING,
    product_id      STRING,
    product_name    STRING,
    category_name   STRING,
    channel         STRING,
    province        STRING,
    total_amount    DECIMAL(16,2),
    pay_status      STRING,
    create_time     TIMESTAMP(3),
    dt              STRING,
    PRIMARY KEY (order_id) NOT ENFORCED
) WITH (
    'connector' = 'paimon',
    'bucket' = '2'
);

-- 2. ODS → DWD：过滤脏数据 + 生成 dt 字段
INSERT INTO dwd_orders
SELECT
    order_id,
    platform_code,
    shop_id,
    buyer_uid,
    product_id,
    product_name,
    category_name,
    channel,
    province,
    total_amount,
    pay_status,
    create_time,
    DATE_FORMAT(create_time, 'yyyyMMdd') AS dt
FROM ods_orders
WHERE order_id IS NOT NULL
  AND total_amount IS NOT NULL
  AND total_amount > 0;
