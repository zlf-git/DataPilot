-- ============================================================
-- 作业1：MySQL CDC → Kafka（1 事实表 orders + 3 维度表，共 4 表）
-- 事实表订单 → ods_douyin_order_cdc；维度表 → ods_dim_*_cdc
-- 4 个 CDC source 的 server-id 互不冲突（订单并行 5400-5404，维度各 1 个）
-- ============================================================

SET 'execution.checkpointing.interval' = '10 s';
SET 'execution.checkpointing.mode' = 'EXACTLY_ONCE';
SET 'execution.restart-strategy' = 'fixed-delay';
SET 'execution.restart-strategy.fixed-delay.attempts' = '10';
SET 'execution.restart-strategy.fixed-delay.delay' = '10s';
SET 'parallelism.default' = '1';

-- ==================== 1. CDC 源表（4 张） ====================
-- 事实表 orders
CREATE TABLE IF NOT EXISTS orders_cdc_source (
    id            BIGINT,
    order_id      STRING,
    platform_code STRING,
    shop_id       STRING,
    buyer_uid     STRING,
    product_id    STRING,
    channel       STRING,
    province      STRING,
    total_amount  DECIMAL(16,2),
    pay_status    STRING,
    create_time   TIMESTAMP(3),
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

-- 商品维度
CREATE TABLE IF NOT EXISTS dim_product_cdc_source (
    product_id    STRING,
    product_name  STRING,
    category_name STRING,
    PRIMARY KEY (product_id) NOT ENFORCED
) WITH (
    'connector' = 'mysql-cdc',
    'hostname' = 'mysql-ai-analysis',
    'port' = '3306',
    'username' = 'root',
    'password' = 'root123',
    'database-name' = 'cdc_source',
    'table-name' = 'dim_product',
    'server-id' = '5405',
    'server-time-zone' = 'UTC',
    'scan.startup.mode' = 'initial'
);

-- 渠道维度
CREATE TABLE IF NOT EXISTS dim_channel_cdc_source (
    channel_code STRING,
    channel_name STRING,
    PRIMARY KEY (channel_code) NOT ENFORCED
) WITH (
    'connector' = 'mysql-cdc',
    'hostname' = 'mysql-ai-analysis',
    'port' = '3306',
    'username' = 'root',
    'password' = 'root123',
    'database-name' = 'cdc_source',
    'table-name' = 'dim_channel',
    'server-id' = '5406',
    'server-time-zone' = 'UTC',
    'scan.startup.mode' = 'initial'
);

-- 省份维度
CREATE TABLE IF NOT EXISTS dim_province_cdc_source (
    province_code STRING,
    province_name STRING,
    region        STRING,
    PRIMARY KEY (province_code) NOT ENFORCED
) WITH (
    'connector' = 'mysql-cdc',
    'hostname' = 'mysql-ai-analysis',
    'port' = '3306',
    'username' = 'root',
    'password' = 'root123',
    'database-name' = 'cdc_source',
    'table-name' = 'dim_province',
    'server-id' = '5407',
    'server-time-zone' = 'UTC',
    'scan.startup.mode' = 'initial'
);

-- ==================== 2. Kafka Sink（4 个 topic） ====================
-- 分区设计：一张源表对应一个 topic（不是分区）；topic 内部按主键 hash 分多个分区。
--   sink.partitioner='default' = 按主键 hash 分区（同一条记录增删改进同一分区，保证有序）。
--   orders 事实表 topic 为 3 分区（并行消费+同主键有序）；dim_* 维度表各 3-5 行，
--   数据量极小，单分区足够，无需多分区（bitnami auto.create 默认 1 分区）。
CREATE TABLE IF NOT EXISTS orders_kafka_sink (
    id            BIGINT,
    order_id      STRING,
    platform_code STRING,
    shop_id       STRING,
    buyer_uid     STRING,
    product_id    STRING,
    channel       STRING,
    province      STRING,
    total_amount  DECIMAL(16,2),
    pay_status    STRING,
    create_time   TIMESTAMP(3),
    PRIMARY KEY (id) NOT ENFORCED
) WITH (
    'connector' = 'kafka',
    'topic' = 'ods_douyin_order_cdc',
    'properties.bootstrap.servers' = 'kafka-ai-analysis:9092',
    'properties.group.id' = 'flink_cdc_mysql_to_kafka_order',
    'format' = 'debezium-json',
    'sink.partitioner' = 'default'
);

CREATE TABLE IF NOT EXISTS dim_product_kafka_sink (
    product_id    STRING,
    product_name  STRING,
    category_name STRING,
    PRIMARY KEY (product_id) NOT ENFORCED
) WITH (
    'connector' = 'kafka',
    'topic' = 'ods_dim_product_cdc',
    'properties.bootstrap.servers' = 'kafka-ai-analysis:9092',
    'properties.group.id' = 'flink_cdc_mysql_to_kafka_dim_product',
    'format' = 'debezium-json',
    'sink.partitioner' = 'default'
);

CREATE TABLE IF NOT EXISTS dim_channel_kafka_sink (
    channel_code STRING,
    channel_name STRING,
    PRIMARY KEY (channel_code) NOT ENFORCED
) WITH (
    'connector' = 'kafka',
    'topic' = 'ods_dim_channel_cdc',
    'properties.bootstrap.servers' = 'kafka-ai-analysis:9092',
    'properties.group.id' = 'flink_cdc_mysql_to_kafka_dim_channel',
    'format' = 'debezium-json',
    'sink.partitioner' = 'default'
);

CREATE TABLE IF NOT EXISTS dim_province_kafka_sink (
    province_code STRING,
    province_name STRING,
    region        STRING,
    PRIMARY KEY (province_code) NOT ENFORCED
) WITH (
    'connector' = 'kafka',
    'topic' = 'ods_dim_province_cdc',
    'properties.bootstrap.servers' = 'kafka-ai-analysis:9092',
    'properties.group.id' = 'flink_cdc_mysql_to_kafka_dim_province',
    'format' = 'debezium-json',
    'sink.partitioner' = 'default'
);

-- ==================== 3. 启动 4 路 CDC 同步 ====================
-- 注意：Dinky 会自动把多个 INSERT 合并为一个 statement set 提交。
INSERT INTO orders_kafka_sink
SELECT id, order_id, platform_code, shop_id, buyer_uid, product_id,
       channel, province, total_amount, pay_status, create_time
FROM orders_cdc_source;

INSERT INTO dim_product_kafka_sink
SELECT product_id, product_name, category_name
FROM dim_product_cdc_source;

INSERT INTO dim_channel_kafka_sink
SELECT channel_code, channel_name
FROM dim_channel_cdc_source;

INSERT INTO dim_province_kafka_sink
SELECT province_code, province_name, region
FROM dim_province_cdc_source;
