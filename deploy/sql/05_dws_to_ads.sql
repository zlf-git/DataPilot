-- ============================================================
-- 作业5：Paimon DWS → Paimon ADS（批模式，T+1 全量重算，写湖）
-- 架构：算一次写湖（Paimon ADS 冷存储）→ job6 从湖同步仓（StarRocks ADS 热查询）
-- 说明：
--   1. 批模式由 Dinky 作业的 batch_model 字段控制（提交时 batchModel=true），
--      不能在 SQL 里 SET 'execution.runtime-mode'（TableEnvironment 已实例化）。
--      批模式下无界 GROUP BY 状态不增长，COUNT(DISTINCT) 是全量聚合、结果确定。
--   2. 写湖与同步仓拆成两个作业：批模式下多个 INSERT 无依赖会并行，若同作业
--      「同步仓」会在「写湖」commit 前读到空表。拆开后 job6 读完整快照。
--   3. visit_uv = uv * 10 是【模拟数据】：真实访问 UV 需埋点/访问日志，此处假设
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
-- 注意：ads_trade_overview_di 每个 dt 只有一行，自然主键只有 dt，但 Paimon
-- 分区主键表要求「主键不能全等于分区字段」，故加常量业务键 biz_type 兜底
--（biz_type 恒 'TRADE'），同步 StarRocks 时排除该列，StarRocks 侧仍是主键 dt。
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

-- ============================================================
-- 3. 写湖（算一次，INSERT OVERWRITE 覆盖）
-- ============================================================

-- 3.1 经营总览（从 DWD 明细聚合，覆盖写湖；biz_type 恒 'TRADE' 为分区主键兜底列）
INSERT OVERWRITE paimon_catalog.mall_dw.ads_trade_overview_di
SELECT
    dt,
    'TRADE'                                                          AS biz_type,
    COUNT(*)                                                         AS total_order_cnt,
    SUM(CASE WHEN pay_status = 'PAID' THEN 1 ELSE 0 END)             AS paid_order_cnt,
    SUM(CASE WHEN pay_status = 'PAID' THEN total_amount ELSE 0 END)  AS total_gmv,
    COUNT(DISTINCT CASE WHEN pay_status = 'PAID' THEN buyer_uid END) AS paid_user_cnt
FROM paimon_catalog.mall_dw.dwd_orders
GROUP BY dt;

-- 3.2 渠道转化（从 DWS 渠道汇总，覆盖写湖；visit_uv 为模拟值，见文件头说明）
INSERT OVERWRITE paimon_catalog.mall_dw.ads_channel_conversion_di
SELECT
    dt,
    channel,
    uv * 10                                                    AS visit_uv,  -- 模拟访问 UV
    order_cnt,
    paid_order_cnt,
    gmv,
    CAST(order_cnt AS DECIMAL(16,4)) / NULLIF(uv * 10, 0)       AS order_conversion_rate,
    CAST(paid_order_cnt AS DECIMAL(16,4)) / NULLIF(uv * 10, 0)  AS pay_conversion_rate
FROM paimon_catalog.mall_dw.dws_channel_order_day;

-- 3.3 地区 GMV（从 DWS 省份汇总，覆盖写湖）
INSERT OVERWRITE paimon_catalog.mall_dw.ads_region_gmv_di
SELECT dt, province, order_cnt, paid_order_cnt, paid_user_cnt, gmv
FROM paimon_catalog.mall_dw.dws_province_order_day;

-- 3.4 商品排行（从 DWS 商品汇总，覆盖写湖）
INSERT OVERWRITE paimon_catalog.mall_dw.ads_product_sales_rank_di
SELECT dt, product_id, product_name, category_name, sale_cnt, sale_amount
FROM paimon_catalog.mall_dw.dws_product_sales_day;
