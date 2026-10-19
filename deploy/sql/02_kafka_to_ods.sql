-- ============================================================
-- 作业2：Kafka(debezium-json) → Paimon ODS（湖留存：贴源层）
-- 链路：ods_douyin_order_cdc → paimon_catalog.mall_dw.ods_orders
-- ============================================================

SET 'execution.checkpointing.interval' = '10 s';
SET 'execution.checkpointing.mode' = 'EXACTLY_ONCE';
SET 'table.exec.sink.upsert-materialize' = 'NONE';

-- 1. Paimon Catalog（元数据注册 Hive Metastore，数据落 HDFS）
CREATE CATALOG IF NOT EXISTS paimon_catalog WITH (
    'type' = 'paimon',
    'warehouse' = 'hdfs://hadoop-namenode:9000/paimon_warehouse',
    'metastore' = 'hive',
    'uri' = 'thrift://hive-metastore:9083'
);

USE CATALOG paimon_catalog;
CREATE DATABASE IF NOT EXISTS mall_dw;
USE mall_dw;

-- 2. Paimon ODS 表（贴源，主键 upsert）
CREATE TABLE IF NOT EXISTS ods_orders (
    id              BIGINT,
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
    PRIMARY KEY (id, dt) NOT ENFORCED
) PARTITIONED BY (dt) WITH (
    'connector' = 'paimon',
    'changelog-producer' = 'input',
    'bucket' = '2'
);

USE CATALOG default_catalog;

-- 3. Kafka CDC source（debezium-json）
CREATE TEMPORARY TABLE kafka_cdc_source (
    id              BIGINT,
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
    create_time     TIMESTAMP(3)
) WITH (
    'connector' = 'kafka',
    'topic' = 'ods_douyin_order_cdc',
    'properties.bootstrap.servers' = 'kafka-ai-analysis:9092',
    'properties.group.id' = 'flink_paimon_ods_orders',
    'format' = 'debezium-json',
    'scan.startup.mode' = 'earliest-offset',
    'debezium-json.timestamp-format.standard' = 'SQL'
);

-- 4. 写入 Paimon ODS
USE CATALOG paimon_catalog;
USE mall_dw;

INSERT INTO ods_orders
SELECT id, order_id, platform_code, shop_id, buyer_uid, product_id, product_name,
       category_name, channel, province, total_amount, pay_status, create_time,
       DATE_FORMAT(create_time, 'yyyyMMdd') AS dt
FROM default_catalog.default_database.kafka_cdc_source;
