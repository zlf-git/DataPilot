-- ============================================================
-- 作业1：MySQL CDC → Kafka（debezium-json）
-- 链路：cdc_source.orders 的 binlog → Kafka ods_douyin_order_cdc
-- 适配：Flink 1.20 + MySQL CDC 3.2.1 + Kafka 3.3.0
-- ============================================================

SET 'execution.checkpointing.interval' = '10 s';
SET 'execution.checkpointing.mode' = 'EXACTLY_ONCE';
SET 'execution.restart-strategy' = 'fixed-delay';
SET 'execution.restart-strategy.fixed-delay.attempts' = '10';
SET 'execution.restart-strategy.fixed-delay.delay' = '10s';

-- 1. MySQL CDC 源表
CREATE TABLE IF NOT EXISTS orders_cdc_source (
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
    PRIMARY KEY (id) NOT ENFORCED
) WITH (
    'connector' = 'mysql-cdc',
    'hostname' = 'mysql-ai-analysis',
    'port' = '3306',
    'username' = 'root',
    'password' = 'root123',
    'database-name' = 'cdc_source',
    'table-name' = 'orders',
    'server-id' = '5400-5404',
    'server-time-zone' = 'UTC',
    'scan.startup.mode' = 'initial'
);

-- 2. Kafka Sink（debezium-json）
CREATE TABLE IF NOT EXISTS kafka_cdc_sink (
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
    PRIMARY KEY (id) NOT ENFORCED
) WITH (
    'connector' = 'kafka',
    'topic' = 'ods_douyin_order_cdc',
    'properties.bootstrap.servers' = 'kafka-ai-analysis:9092',
    'properties.group.id' = 'flink_cdc_mysql_to_kafka',
    'format' = 'debezium-json',
    'sink.partitioner' = 'fixed'
);

-- 3. 启动 CDC 同步
INSERT INTO kafka_cdc_sink
SELECT id, order_id, platform_code, shop_id, buyer_uid, product_id, product_name,
       category_name, channel, province, total_amount, pay_status, create_time
FROM orders_cdc_source;
