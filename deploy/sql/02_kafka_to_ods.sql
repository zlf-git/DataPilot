-- ============================================================
-- 作业2：Kafka(debezium-json) → Paimon（ODS 贴源层 + DIM 维度层）
-- 链路：4 个 Kafka topic → ods_orders（事实）+ dim_product/dim_channel/dim_province（维度）
-- ============================================================

SET 'execution.checkpointing.interval' = '10 s';
SET 'execution.checkpointing.mode' = 'EXACTLY_ONCE';
SET 'execution.restart-strategy' = 'fixed-delay';
SET 'execution.restart-strategy.fixed-delay.attempts' = '10';
SET 'execution.restart-strategy.fixed-delay.delay' = '10s';
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

-- ==================== 2. Paimon 表 ====================
-- 2.1 ODS 事实表（贴源，主键 upsert，按 dt 分区）
CREATE TABLE IF NOT EXISTS ods_orders (
    id              BIGINT,
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
    PRIMARY KEY (id, dt) NOT ENFORCED
) PARTITIONED BY (dt) WITH (
    'changelog-producer' = 'input',
    'bucket' = '2'
);

-- 2.2 DIM 维度表（全量快照主键表，无日期维度，按 bucket 分桶存储）
--     维度表是「最新状态快照」，靠主键 upsert 覆盖，不按 dt 分区（无日期语义）。
CREATE TABLE IF NOT EXISTS dim_product (
    product_id    STRING,
    product_name  STRING,
    category_name STRING,
    PRIMARY KEY (product_id) NOT ENFORCED
) WITH ('changelog-producer' = 'input', 'bucket' = '1');

CREATE TABLE IF NOT EXISTS dim_channel (
    channel_code STRING,
    channel_name STRING,
    PRIMARY KEY (channel_code) NOT ENFORCED
) WITH ('changelog-producer' = 'input', 'bucket' = '1');

CREATE TABLE IF NOT EXISTS dim_province (
    province_code STRING,
    province_name STRING,
    region        STRING,
    PRIMARY KEY (province_code) NOT ENFORCED
) WITH ('changelog-producer' = 'input', 'bucket' = '1');

USE CATALOG default_catalog;

-- ==================== 3. Kafka CDC source（4 个） ====================
CREATE TEMPORARY TABLE kafka_order_cdc (
    id              BIGINT,
    order_id        STRING,
    platform_code   STRING,
    shop_id         STRING,
    buyer_uid       STRING,
    product_id      STRING,
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

CREATE TEMPORARY TABLE kafka_dim_product_cdc (
    product_id    STRING,
    product_name  STRING,
    category_name STRING
) WITH (
    'connector' = 'kafka',
    'topic' = 'ods_dim_product_cdc',
    'properties.bootstrap.servers' = 'kafka-ai-analysis:9092',
    'properties.group.id' = 'flink_paimon_dim_product',
    'format' = 'debezium-json',
    'scan.startup.mode' = 'earliest-offset'
);

CREATE TEMPORARY TABLE kafka_dim_channel_cdc (
    channel_code STRING,
    channel_name STRING
) WITH (
    'connector' = 'kafka',
    'topic' = 'ods_dim_channel_cdc',
    'properties.bootstrap.servers' = 'kafka-ai-analysis:9092',
    'properties.group.id' = 'flink_paimon_dim_channel',
    'format' = 'debezium-json',
    'scan.startup.mode' = 'earliest-offset'
);

CREATE TEMPORARY TABLE kafka_dim_province_cdc (
    province_code STRING,
    province_name STRING,
    region        STRING
) WITH (
    'connector' = 'kafka',
    'topic' = 'ods_dim_province_cdc',
    'properties.bootstrap.servers' = 'kafka-ai-analysis:9092',
    'properties.group.id' = 'flink_paimon_dim_province',
    'format' = 'debezium-json',
    'scan.startup.mode' = 'earliest-offset'
);

-- ==================== 4. 写入 Paimon ====================
USE CATALOG paimon_catalog;
USE mall_dw;

INSERT INTO ods_orders
SELECT id, order_id, platform_code, shop_id, buyer_uid, product_id,
       channel, province, total_amount, pay_status, create_time,
       DATE_FORMAT(create_time, 'yyyyMMdd') AS dt
FROM default_catalog.default_database.kafka_order_cdc;

INSERT INTO dim_product
SELECT product_id, product_name, category_name
FROM default_catalog.default_database.kafka_dim_product_cdc;

INSERT INTO dim_channel
SELECT channel_code, channel_name
FROM default_catalog.default_database.kafka_dim_channel_cdc;

INSERT INTO dim_province
SELECT province_code, province_name, region
FROM default_catalog.default_database.kafka_dim_province_cdc;
