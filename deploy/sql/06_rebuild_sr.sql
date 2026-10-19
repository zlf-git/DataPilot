-- ============================================================
-- 作业6：从 Paimon DWS 全量重建 StarRocks（批模式，手动/DQC 触发）
-- 用途：① StarRocks 数据损坏后重建；② DWS 口径变更后回刷；③ sink 修复后重灌。
-- 说明：
--   1. 批模式由 Dinky 提交时的 batchModel 字段控制，本文件不写 SET runtime-mode
--      （Dinky 的 TableEnvironment 已实例化，写 SET 会报错）。
--   2. sink DDL 与 05_dws_to_sr.sql 完全一致，两种写法共用同一套 SR 表结构。
--   3. 不要加 WHERE dt = ...，重建的语义就是全量。
--   4. 提交前先在 StarRocks 执行 TRUNCATE TABLE <表名>，否则历史已删除的行会
--      残留（主键表只 upsert 不删）。
--   5. 必须先停 05 的流作业，否则批流同时写同一张表，结果不确定。
-- ============================================================

SET 'parallelism.default' = '1';

CREATE CATALOG IF NOT EXISTS paimon_catalog WITH (
    'type' = 'paimon',
    'warehouse' = 'hdfs://hadoop-namenode:9000/paimon_warehouse',
    'metastore' = 'hive',
    'uri' = 'thrift://hive-metastore:9083'
);

USE CATALOG default_catalog;

-- ==================== StarRocks sink（与 05 完全一致） ====================

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

-- ==================== 全量重建（不分区过滤，语义就是全量） ====================

INSERT INTO sr_trade_overview
SELECT CAST(TO_TIMESTAMP(dt, 'yyyyMMdd') AS DATE) AS dt,
       total_order_cnt, paid_order_cnt, total_gmv, paid_user_cnt
FROM paimon_catalog.mall_dw.dws_trade_overview_day;

INSERT INTO sr_channel_conversion
SELECT CAST(TO_TIMESTAMP(dt, 'yyyyMMdd') AS DATE) AS dt,
       channel, channel_name,
       uv * 10                                                        AS visit_uv,
       order_cnt, paid_order_cnt, gmv,
       CAST(order_cnt AS DECIMAL(16,4)) / NULLIF(uv * 10, 0)          AS order_conversion_rate,
       CAST(paid_order_cnt AS DECIMAL(16,4)) / NULLIF(uv * 10, 0)     AS pay_conversion_rate
FROM paimon_catalog.mall_dw.dws_channel_order_day;

INSERT INTO sr_region_gmv
SELECT CAST(TO_TIMESTAMP(dt, 'yyyyMMdd') AS DATE) AS dt,
       province, province_name, region, order_cnt, paid_order_cnt, paid_user_cnt, gmv
FROM paimon_catalog.mall_dw.dws_province_order_day;

INSERT INTO sr_product_sales
SELECT CAST(TO_TIMESTAMP(dt, 'yyyyMMdd') AS DATE) AS dt,
       product_id, product_name, category_name, sale_cnt, sale_amount
FROM paimon_catalog.mall_dw.dws_product_sales_day;
