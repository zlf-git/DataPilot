-- ============================================================
-- 作业5：Paimon DWS → Paimon ADS（批模式，T+1 全量覆盖写湖，只读 DWS 不再扫明细）
-- 说明：
--   1. 批模式由 Dinky 作业的 batch_model 字段控制（提交时 batchModel=true），
--      不能在 SQL 里 SET 'execution.runtime-mode'（TableEnvironment 已实例化）。
--   2. 4 个 INSERT 全部只读 DWS（已聚合的小表），不再扫 dwd_orders 明细全表，
--      数据量涨到一年也只是重算「每天几行」的 DWS，不再越跑越慢。
--   3. trade_overview 已在 job4 流式维护，这里直接读 dws_trade_overview_day。
--   4. visit_uv = uv * 10 是【模拟数据】：真实访问 UV 需埋点/访问日志，此处假设
--      访问→购买转化率约 10%，仅用于演示大屏，面试/上线前须替换为真实 UV 来源。
-- ============================================================

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
-- ads_trade_overview_di 每个 dt 只有一行，自然主键只有 dt，但 Paimon 分区主键表
-- 要求「主键不能全等于分区字段」，故加常量业务键 biz_type 兜底（恒 'TRADE'）。
CREATE TABLE IF NOT EXISTS ads_trade_overview_di (
    dt               STRING,
    biz_type         STRING,
    total_order_cnt  BIGINT,
    paid_order_cnt   BIGINT,
    total_gmv        DECIMAL(16,2),
    paid_user_cnt    BIGINT,
    PRIMARY KEY (dt, biz_type) NOT ENFORCED
) PARTITIONED BY (dt) WITH ('bucket' = '1');

CREATE TABLE IF NOT EXISTS ads_channel_conversion_di (
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
) PARTITIONED BY (dt) WITH ('bucket' = '1');

CREATE TABLE IF NOT EXISTS ads_region_gmv_di (
    dt               STRING,
    province         STRING,
    province_name    STRING,
    region           STRING,
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

-- ============================================================
-- 3. 写湖（INSERT OVERWRITE 覆盖，全部只读 DWS）
-- ============================================================

-- 3.1 经营总览 ← dws_trade_overview_day（已流式维护，不再扫明细）
INSERT OVERWRITE ads_trade_overview_di
SELECT dt, biz_type, total_order_cnt, paid_order_cnt, total_gmv, paid_user_cnt
FROM dws_trade_overview_day;

-- 3.2 渠道转化 ← dws_channel_order_day（visit_uv 为模拟值，见文件头说明）
INSERT OVERWRITE ads_channel_conversion_di
SELECT
    dt,
    channel,
    channel_name,
    uv * 10                                                    AS visit_uv,  -- 模拟访问 UV
    order_cnt,
    paid_order_cnt,
    gmv,
    CAST(order_cnt AS DECIMAL(16,4)) / NULLIF(uv * 10, 0)       AS order_conversion_rate,
    CAST(paid_order_cnt AS DECIMAL(16,4)) / NULLIF(uv * 10, 0)  AS pay_conversion_rate
FROM dws_channel_order_day;

-- 3.3 地区 GMV ← dws_province_order_day
INSERT OVERWRITE ads_region_gmv_di
SELECT dt, province, province_name, region, order_cnt, paid_order_cnt, paid_user_cnt, gmv
FROM dws_province_order_day;

-- 3.4 商品排行 ← dws_product_sales_day
INSERT OVERWRITE ads_product_sales_rank_di
SELECT dt, product_id, product_name, category_name, sale_cnt, sale_amount
FROM dws_product_sales_day;
