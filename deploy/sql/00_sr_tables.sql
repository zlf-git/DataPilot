-- ============================================================
-- StarRocks 内表 DDL（mall_dw 库）
-- 架构定位：StarRocks 只承接 ADS 4 张内表（job6 从 Paimon 湖同步仓，热查询）。
--   DIM/DWD/DWS 三层数据只落 Paimon 湖（HDFS /paimon_warehouse），StarRocks 不落。
--   维度描述（channel_name / province_name / region / product_name / category_name）
--   已在 DWS 层 join 维度表拼好，随 ADS 冗余进 StarRocks，Agent 直接查中文名。
-- 主键表（PRIMARY KEY）承接 Flink 直写，实现 upsert 去重。
-- ============================================================

CREATE DATABASE IF NOT EXISTS mall_dw;

-- ==================== ADS 应用层（4 张，job6 热写） ====================

-- 1. 经营总览
CREATE TABLE IF NOT EXISTS mall_dw.ads_trade_overview_di (
    dt               VARCHAR(10),
    total_order_cnt  BIGINT,
    paid_order_cnt   BIGINT,
    total_gmv        DECIMAL(16,2),
    paid_user_cnt    BIGINT
)
PRIMARY KEY (dt)
DISTRIBUTED BY HASH(dt) BUCKETS 1;

-- 2. 渠道转化（channel_name 为维表拼回的渠道中文名）
CREATE TABLE IF NOT EXISTS mall_dw.ads_channel_conversion_di (
    dt                    VARCHAR(10),
    channel               VARCHAR(32),
    channel_name          VARCHAR(32),
    visit_uv              BIGINT,
    order_cnt             BIGINT,
    paid_order_cnt        BIGINT,
    gmv                   DECIMAL(16,2),
    order_conversion_rate DECIMAL(16,4),
    pay_conversion_rate   DECIMAL(16,4)
)
PRIMARY KEY (dt, channel)
DISTRIBUTED BY HASH(dt, channel) BUCKETS 1;

-- 3. 地区 GMV（province_name / region 为维表拼回的省份中文名与大区）
CREATE TABLE IF NOT EXISTS mall_dw.ads_region_gmv_di (
    dt               VARCHAR(10),
    province         VARCHAR(32),
    province_name    VARCHAR(64),
    region           VARCHAR(32),
    order_cnt        BIGINT,
    paid_order_cnt   BIGINT,
    paid_user_cnt    BIGINT,
    gmv              DECIMAL(16,2)
)
PRIMARY KEY (dt, province)
DISTRIBUTED BY HASH(dt, province) BUCKETS 1;

-- 4. 商品排行（product_name / category_name 为维表拼回的商品名与类目）
CREATE TABLE IF NOT EXISTS mall_dw.ads_product_sales_rank_di (
    dt               VARCHAR(10),
    product_id       VARCHAR(64),
    product_name     VARCHAR(128),
    category_name    VARCHAR(64),
    sale_cnt         BIGINT,
    sale_amount      DECIMAL(16,2)
)
PRIMARY KEY (dt, product_id)
DISTRIBUTED BY HASH(dt, product_id) BUCKETS 1;
