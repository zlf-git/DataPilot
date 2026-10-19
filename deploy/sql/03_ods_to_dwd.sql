-- ============================================================
-- 作业3：Paimon ODS → Paimon DWD（湖留存：事实明细层清洗 + dt 字段）
-- 链路：ods_orders → dwd_orders
-- 事实表只存维度外键（product_id/channel/province），维度描述由下游 join dim_* 补齐
-- ============================================================

SET 'execution.checkpointing.interval' = '10 s';
SET 'execution.checkpointing.mode' = 'EXACTLY_ONCE';
SET 'execution.restart-strategy' = 'fixed-delay';
SET 'execution.restart-strategy.fixed-delay.attempts' = '10';
SET 'execution.restart-strategy.fixed-delay.delay' = '10s';
SET 'table.exec.sink.upsert-materialize' = 'NONE';

CREATE CATALOG IF NOT EXISTS paimon_catalog WITH (
    'type' = 'paimon',
    'warehouse' = 'hdfs://hadoop-namenode:9000/paimon_warehouse',
    'metastore' = 'hive',
    'uri' = 'thrift://hive-metastore:9083'
);

USE CATALOG paimon_catalog;
USE mall_dw;

-- 1. DWD 订单事实明细表（主键 order_id，只存维度外键，含标准化 dt 字段）
CREATE TABLE IF NOT EXISTS dwd_orders (
    order_id        STRING,
    platform_code   STRING,
    shop_id         STRING,
    buyer_uid       STRING,
    product_id      STRING,
    channel         STRING,
    province        STRING,
    total_amount    DECIMAL(16,2),
    pay_status      STRING,
    create_time     TIMESTAMP(3),
    dt              STRING,
    PRIMARY KEY (order_id, dt) NOT ENFORCED
) PARTITIONED BY (dt) WITH (
    'changelog-producer' = 'input',
    'bucket' = '2'
);

-- 2. ODS → DWD：过滤脏数据 + 生成 dt 字段（保留维度外键，不冗余维度描述）
INSERT INTO dwd_orders
SELECT
    order_id,
    platform_code,
    shop_id,
    buyer_uid,
    product_id,
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
