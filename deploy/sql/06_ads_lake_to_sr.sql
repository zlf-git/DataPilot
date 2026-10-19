-- ============================================================
-- 作业6：Paimon ADS → StarRocks ADS（批模式，从湖同步仓，不重算）
-- 架构：读 Paimon ADS 快照（job5 已算好写湖）→ 写 StarRocks 内表（热查询）
-- 说明：
--   1. 批模式由 Dinky 作业的 batch_model 字段控制（提交时 batchModel=true）。
--   2. 本作业只做「湖 → 仓」搬运，不重复扫描 dwd/dws，聚合已在 job5 完成。
--   3. ads_trade_overview_di 在 Paimon 侧有 biz_type 兜底列（分区主键约束），
--      StarRocks 侧主键只有 dt，故 SELECT 排除 biz_type。
--   4. load-url 用 8080 是 starrocks/allin1 镜像的 feproxy 端口（非 Web UI）：
--      allin1 的 BE 被 entrypoint 注册为 127.0.0.1，connector 直连 8030/8040 会
--      Connection refused；feproxy（nginx 8080）在容器内跟随 FE 307 重定向到 BE。
-- ============================================================

SET 'parallelism.default' = '1';

-- 1. Paimon Catalog（读 Paimon ADS）
CREATE CATALOG IF NOT EXISTS paimon_catalog WITH (
    'type' = 'paimon',
    'warehouse' = 'hdfs://hadoop-namenode:9000/paimon_warehouse',
    'metastore' = 'hive',
    'uri' = 'thrift://hive-metastore:9083'
);

USE CATALOG default_catalog;

-- 2. StarRocks ADS sink（仓热查询，4 张）
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
    channel_name          STRING,
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
    province_name    STRING,
    region           STRING,
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

-- ============================================================
-- 3. 从湖同步仓（不重算，只搬运 job5 已聚合好的数据）
-- ============================================================

-- 3.1 经营总览（排除 biz_type 兜底列，StarRocks 侧主键只有 dt）
INSERT INTO sr_ads_trade
SELECT dt, total_order_cnt, paid_order_cnt, total_gmv, paid_user_cnt
FROM paimon_catalog.mall_dw.ads_trade_overview_di;

-- 3.2 渠道转化
INSERT INTO sr_ads_channel
SELECT dt, channel, channel_name, visit_uv, order_cnt, paid_order_cnt, gmv,
       order_conversion_rate, pay_conversion_rate
FROM paimon_catalog.mall_dw.ads_channel_conversion_di;

-- 3.3 地区 GMV
INSERT INTO sr_ads_region
SELECT dt, province, province_name, region, order_cnt, paid_order_cnt, paid_user_cnt, gmv
FROM paimon_catalog.mall_dw.ads_region_gmv_di;

-- 3.4 商品排行
INSERT INTO sr_ads_product
SELECT dt, product_id, product_name, category_name, sale_cnt, sale_amount
FROM paimon_catalog.mall_dw.ads_product_sales_rank_di;
