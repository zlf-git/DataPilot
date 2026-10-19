-- ============================================================
-- 作业5：Paimon DWS → StarRocks（流式）。ADS 不落湖，SR 是唯一应用层存储。
-- 链路：job4 无界聚合维护 4 张 DWS（changelog-producer=input），本作业流读
--       DWS 的 changelog（-U/+U/+I）直接 upsert 写 StarRocks 主键表。
-- 消费进度记在 checkpoint，重启从位点续读，不重放全量。
-- 关键设计：
--   1. dt 在湖里是 STRING 'yyyyMMdd'，SR 里是 DATE，必须显式 TO_TIMESTAMP 转 DATE，
--      否则两边类型不一致时不会报错、只会永远不命中。
--   2. load-url 用 8080：starrocks/allin1 的 BE 被 entrypoint 注册为 127.0.0.1，
--      connector 直连 8030/8040 会 Connection refused；8080 是 feproxy（nginx），
--      容器内跟随 FE 307 重定向到 BE。jdbc-url 仍用 9030。
--   3. dws_trade_overview_day 有 biz_type 兜底列（分区主键约束），SR 侧主键只有
--      dt，故投影掉 biz_type（其值恒为 'TRADE'，不影响结果）。
-- ============================================================

SET 'execution.checkpointing.interval' = '60 s';
SET 'execution.checkpointing.mode' = 'EXACTLY_ONCE';
SET 'execution.restart-strategy' = 'fixed-delay';
SET 'execution.restart-strategy.fixed-delay.attempts' = '10';
SET 'execution.restart-strategy.fixed-delay.delay' = '10 s';
SET 'parallelism.default' = '1';

CREATE CATALOG IF NOT EXISTS paimon_catalog WITH (
    'type' = 'paimon',
    'warehouse' = 'hdfs://hadoop-namenode:9000/paimon_warehouse',
    'metastore' = 'hive',
    'uri' = 'thrift://hive-metastore:9083'
);

USE CATALOG default_catalog;

-- ==================== StarRocks sink（4 张，主键 upsert） ====================

-- 1. 经营总览
CREATE TABLE sr_trade_overview (
    dt               DATE,
    total_order_cnt  BIGINT,
    paid_order_cnt   BIGINT,
    total_gmv        DECIMAL(16,2),
    paid_user_cnt    BIGINT,
    PRIMARY KEY (dt) NOT ENFORCED
) WITH (
    'connector'                         = 'starrocks',
    'jdbc-url'                          = 'jdbc:mysql://sr-ai-analysis:9030',
    'load-url'                          = 'sr-ai-analysis:8080',
    'database-name'                     = 'mall_dw',
    'table-name'                        = 'ads_trade_overview_di',
    'username'                          = 'root',
    'password'                          = '',
    'sink.properties.format'            = 'json',
    'sink.properties.strip_outer_array' = 'true',
    'sink.buffer-flush.interval-ms'     = '1000'
);

-- 2. 渠道转化（visit_uv 为模拟值，见注释）
CREATE TABLE sr_channel_conversion (
    dt                    DATE,
    channel               STRING,
    channel_name          STRING,
    visit_uv              BIGINT,
    order_cnt             BIGINT,
    paid_order_cnt        BIGINT,
    gmv                   DECIMAL(16,2),
    order_conversion_rate DECIMAL(16,4),
    pay_conversion_rate   DECIMAL(16,4),
    PRIMARY KEY (dt, channel) NOT ENFORCED
) WITH (
    'connector'                         = 'starrocks',
    'jdbc-url'                          = 'jdbc:mysql://sr-ai-analysis:9030',
    'load-url'                          = 'sr-ai-analysis:8080',
    'database-name'                     = 'mall_dw',
    'table-name'                        = 'ads_channel_conversion_di',
    'username'                          = 'root',
    'password'                          = '',
    'sink.properties.format'            = 'json',
    'sink.properties.strip_outer_array' = 'true',
    'sink.buffer-flush.interval-ms'     = '1000'
);

-- 3. 地区 GMV
CREATE TABLE sr_region_gmv (
    dt               DATE,
    province         STRING,
    province_name    STRING,
    region           STRING,
    order_cnt        BIGINT,
    paid_order_cnt   BIGINT,
    paid_user_cnt    BIGINT,
    gmv              DECIMAL(16,2),
    PRIMARY KEY (dt, province) NOT ENFORCED
) WITH (
    'connector'                         = 'starrocks',
    'jdbc-url'                          = 'jdbc:mysql://sr-ai-analysis:9030',
    'load-url'                          = 'sr-ai-analysis:8080',
    'database-name'                     = 'mall_dw',
    'table-name'                        = 'ads_region_gmv_di',
    'username'                          = 'root',
    'password'                          = '',
    'sink.properties.format'            = 'json',
    'sink.properties.strip_outer_array' = 'true',
    'sink.buffer-flush.interval-ms'     = '1000'
);

-- 4. 商品排行
CREATE TABLE sr_product_sales (
    dt               DATE,
    product_id       STRING,
    product_name     STRING,
    category_name    STRING,
    sale_cnt         BIGINT,
    sale_amount      DECIMAL(16,2),
    PRIMARY KEY (dt, product_id) NOT ENFORCED
) WITH (
    'connector'                         = 'starrocks',
    'jdbc-url'                          = 'jdbc:mysql://sr-ai-analysis:9030',
    'load-url'                          = 'sr-ai-analysis:8080',
    'database-name'                     = 'mall_dw',
    'table-name'                        = 'ads_product_sales_rank_di',
    'username'                          = 'root',
    'password'                          = '',
    'sink.properties.format'            = 'json',
    'sink.properties.strip_outer_array' = 'true',
    'sink.buffer-flush.interval-ms'     = '1000'
);

-- ==================== 流式写入（4 条 INSERT 同作业，共用 checkpoint） ====================

-- 1. 经营总览（投影掉 biz_type）
INSERT INTO sr_trade_overview
SELECT CAST(TO_TIMESTAMP(dt, 'yyyyMMdd') AS DATE) AS dt,
       total_order_cnt, paid_order_cnt, total_gmv, paid_user_cnt
FROM paimon_catalog.mall_dw.dws_trade_overview_day;

-- 2. 渠道转化
--    visit_uv 为模拟值：无真实访问埋点，用订单数的 10 倍充当「访问 UV」，
--    转化率 = 订单数/访问UV。面试须说明这是模拟数据。
INSERT INTO sr_channel_conversion
SELECT CAST(TO_TIMESTAMP(dt, 'yyyyMMdd') AS DATE) AS dt,
       channel, channel_name,
       uv * 10                                                        AS visit_uv,
       order_cnt, paid_order_cnt, gmv,
       CAST(order_cnt AS DECIMAL(16,4)) / NULLIF(uv * 10, 0)          AS order_conversion_rate,
       CAST(paid_order_cnt AS DECIMAL(16,4)) / NULLIF(uv * 10, 0)     AS pay_conversion_rate
FROM paimon_catalog.mall_dw.dws_channel_order_day;

-- 3. 地区 GMV
INSERT INTO sr_region_gmv
SELECT CAST(TO_TIMESTAMP(dt, 'yyyyMMdd') AS DATE) AS dt,
       province, province_name, region, order_cnt, paid_order_cnt, paid_user_cnt, gmv
FROM paimon_catalog.mall_dw.dws_province_order_day;

-- 4. 商品排行
INSERT INTO sr_product_sales
SELECT CAST(TO_TIMESTAMP(dt, 'yyyyMMdd') AS DATE) AS dt,
       product_id, product_name, category_name, sale_cnt, sale_amount
FROM paimon_catalog.mall_dw.dws_product_sales_day;
