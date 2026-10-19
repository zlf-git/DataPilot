-- ============================================================
-- 作业5：Paimon DWS → 双写（Paimon ADS 湖冷存储 + StarRocks ADS 仓热查询）
-- 链路：dws_* / dwd_orders → paimon ADS ×4（湖） + starrocks ADS ×4（仓）
-- ============================================================

SET 'execution.checkpointing.interval' = '10 s';
SET 'execution.checkpointing.mode' = 'EXACTLY_ONCE';
SET 'table.exec.sink.upsert-materialize' = 'NONE';
SET 'parallelism.default' = '1';

-- 1. Paimon Catalog
CREATE CATALOG IF NOT EXISTS paimon_catalog WITH (
    'type' = 'paimon',
    'warehouse' = 'hdfs://hadoop-namenode:9000/paimon_warehouse',
    'metastore' = 'hive',
    'uri' = 'thrift://hive-metastore:9083'
);

USE CATALOG paimon_catalog;
USE mall_dw;

-- 2. Paimon ADS 表（湖冷存储，4 张）
CREATE TABLE IF NOT EXISTS ads_trade_overview_di (
    dt               STRING,
    total_order_cnt  BIGINT,
    paid_order_cnt   BIGINT,
    total_gmv        DECIMAL(16,2),
    paid_user_cnt    BIGINT,
    PRIMARY KEY (dt) NOT ENFORCED
) WITH ('bucket' = '1');

CREATE TABLE IF NOT EXISTS ads_channel_conversion_di (
    dt                    STRING,
    channel               STRING,
    visit_uv              BIGINT,
    order_cnt             BIGINT,
    paid_order_cnt        BIGINT,
    gmv                   DECIMAL(16,2),
    order_conversion_rate DECIMAL(16,4),
    pay_conversion_rate   DECIMAL(16,4),
    PRIMARY KEY (dt, channel) NOT ENFORCED
) PARTITIONED BY (dt) WITH ('bucket' = '1');

CREATE TABLE IF NOT EXISTS ads_region_gmv_di (
    dt               STRING,
    province         STRING,
    order_cnt        BIGINT,
    paid_order_cnt   BIGINT,
    paid_user_cnt    BIGINT,
    gmv              DECIMAL(16,2),
    PRIMARY KEY (dt, province) NOT ENFORCED
) PARTITIONED BY (dt) WITH ('bucket' = '1');

CREATE TABLE IF NOT EXISTS ads_product_sales_rank_di (
    dt               STRING,
    product_id       STRING,
    product_name     STRING,
    category_name    STRING,
    sale_cnt         BIGINT,
    sale_amount      DECIMAL(16,2),
    PRIMARY KEY (dt, product_id) NOT ENFORCED
) PARTITIONED BY (dt) WITH ('bucket' = '1');

-- 3. StarRocks ADS sink（仓热查询，4 张，default_catalog 下）
USE CATALOG default_catalog;

CREATE TABLE sr_ads_trade (
    dt               STRING,
    total_order_cnt  BIGINT,
    paid_order_cnt   BIGINT,
    total_gmv        DECIMAL(16,2),
    paid_user_cnt    BIGINT,
    PRIMARY KEY (dt) NOT ENFORCED
) WITH (
    'connector' = 'starrocks',
    'jdbc-url' = 'jdbc:mysql://sr-ai-analysis:9030',
    'load-url' = 'sr-ai-analysis:8080',
    'database-name' = 'mall_dw',
    'table-name' = 'ads_trade_overview_di',
    'username' = 'root',
    'password' = '',
    'sink.properties.format' = 'json',
    'sink.properties.strip_outer_array' = 'true',
    'sink.buffer-flush.interval-ms' = '5000'
);

CREATE TABLE sr_ads_channel (
    dt                    STRING,
    channel               STRING,
    visit_uv              BIGINT,
    order_cnt             BIGINT,
    paid_order_cnt        BIGINT,
    gmv                   DECIMAL(16,2),
    order_conversion_rate DECIMAL(16,4),
    pay_conversion_rate   DECIMAL(16,4),
    PRIMARY KEY (dt, channel) NOT ENFORCED
) WITH (
    'connector' = 'starrocks',
    'jdbc-url' = 'jdbc:mysql://sr-ai-analysis:9030',
    'load-url' = 'sr-ai-analysis:8080',
    'database-name' = 'mall_dw',
    'table-name' = 'ads_channel_conversion_di',
    'username' = 'root',
    'password' = '',
    'sink.properties.format' = 'json',
    'sink.properties.strip_outer_array' = 'true',
    'sink.buffer-flush.interval-ms' = '5000'
);

CREATE TABLE sr_ads_region (
    dt               STRING,
    province         STRING,
    order_cnt        BIGINT,
    paid_order_cnt   BIGINT,
    paid_user_cnt    BIGINT,
    gmv              DECIMAL(16,2),
    PRIMARY KEY (dt, province) NOT ENFORCED
) WITH (
    'connector' = 'starrocks',
    'jdbc-url' = 'jdbc:mysql://sr-ai-analysis:9030',
    'load-url' = 'sr-ai-analysis:8080',
    'database-name' = 'mall_dw',
    'table-name' = 'ads_region_gmv_di',
    'username' = 'root',
    'password' = '',
    'sink.properties.format' = 'json',
    'sink.properties.strip_outer_array' = 'true',
    'sink.buffer-flush.interval-ms' = '5000'
);

CREATE TABLE sr_ads_product (
    dt               STRING,
    product_id       STRING,
    product_name     STRING,
    category_name    STRING,
    sale_cnt         BIGINT,
    sale_amount      DECIMAL(16,2),
    PRIMARY KEY (dt, product_id) NOT ENFORCED
) WITH (
    'connector' = 'starrocks',
    'jdbc-url' = 'jdbc:mysql://sr-ai-analysis:9030',
    'load-url' = 'sr-ai-analysis:8080',
    'database-name' = 'mall_dw',
    'table-name' = 'ads_product_sales_rank_di',
    'username' = 'root',
    'password' = '',
    'sink.properties.format' = 'json',
    'sink.properties.strip_outer_array' = 'true',
    'sink.buffer-flush.interval-ms' = '5000'
);

-- 4. 双写：每个 ADS 同时落 Paimon（湖）+ StarRocks（仓）
    -- 经营总览（从 Paimon DWD 明细聚合）
    INSERT INTO paimon_catalog.mall_dw.ads_trade_overview_di
    SELECT
        dt,
        COUNT(*)                                                       AS total_order_cnt,
        SUM(CASE WHEN pay_status = 'PAID' THEN 1 ELSE 0 END)           AS paid_order_cnt,
        SUM(CASE WHEN pay_status = 'PAID' THEN total_amount ELSE 0 END) AS total_gmv,
        COUNT(DISTINCT CASE WHEN pay_status = 'PAID' THEN buyer_uid END) AS paid_user_cnt
    FROM paimon_catalog.mall_dw.dwd_orders
    GROUP BY dt;

    INSERT INTO sr_ads_trade
    SELECT
        dt,
        COUNT(*)                                                       AS total_order_cnt,
        SUM(CASE WHEN pay_status = 'PAID' THEN 1 ELSE 0 END)           AS paid_order_cnt,
        SUM(CASE WHEN pay_status = 'PAID' THEN total_amount ELSE 0 END) AS total_gmv,
        COUNT(DISTINCT CASE WHEN pay_status = 'PAID' THEN buyer_uid END) AS paid_user_cnt
    FROM paimon_catalog.mall_dw.dwd_orders
    GROUP BY dt;

    -- 渠道转化（从 Paimon DWS 渠道汇总）
    INSERT INTO paimon_catalog.mall_dw.ads_channel_conversion_di
    SELECT
        dt,
        channel,
        uv * 10                                                       AS visit_uv,
        order_cnt,
        paid_order_cnt,
        gmv,
        CAST(order_cnt AS DECIMAL(16,4)) / NULLIF(uv * 10, 0)         AS order_conversion_rate,
        CAST(paid_order_cnt AS DECIMAL(16,4)) / NULLIF(uv * 10, 0)    AS pay_conversion_rate
    FROM paimon_catalog.mall_dw.dws_channel_order_day;

    INSERT INTO sr_ads_channel
    SELECT
        dt,
        channel,
        uv * 10                                                       AS visit_uv,
        order_cnt,
        paid_order_cnt,
        gmv,
        CAST(order_cnt AS DECIMAL(16,4)) / NULLIF(uv * 10, 0)         AS order_conversion_rate,
        CAST(paid_order_cnt AS DECIMAL(16,4)) / NULLIF(uv * 10, 0)    AS pay_conversion_rate
    FROM paimon_catalog.mall_dw.dws_channel_order_day;

    -- 地区 GMV（从 Paimon DWS 省份汇总）
    INSERT INTO paimon_catalog.mall_dw.ads_region_gmv_di
    SELECT dt, province, order_cnt, paid_order_cnt, paid_user_cnt, gmv
    FROM paimon_catalog.mall_dw.dws_province_order_day;

    INSERT INTO sr_ads_region
    SELECT dt, province, order_cnt, paid_order_cnt, paid_user_cnt, gmv
    FROM paimon_catalog.mall_dw.dws_province_order_day;

    -- 商品排行（从 Paimon DWS 商品汇总）
    INSERT INTO paimon_catalog.mall_dw.ads_product_sales_rank_di
    SELECT dt, product_id, product_name, category_name, sale_cnt, sale_amount
    FROM paimon_catalog.mall_dw.dws_product_sales_day;

    INSERT INTO sr_ads_product
    SELECT dt, product_id, product_name, category_name, sale_cnt, sale_amount
    FROM paimon_catalog.mall_dw.dws_product_sales_day;
