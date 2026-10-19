-- ============================================================
-- StarRocks 内表 DDL（mall_dw 库，四层：ODS / DWD / DWS / ADS）
-- 主键表（PRIMARY KEY）承接 Flink 直写，实现 upsert 去重
-- 主查询链路：Flink 写内表，StarRocks 不读 Paimon（规避版本冲突）
-- ============================================================

CREATE DATABASE IF NOT EXISTS mall_dw;

-- ==================== ODS 贴源层 ====================
CREATE TABLE IF NOT EXISTS mall_dw.ods_orders (
    id              BIGINT,
    order_id        VARCHAR(64),
    platform_code   VARCHAR(32),
    shop_id         VARCHAR(32),
    buyer_uid       VARCHAR(64),
    product_id      VARCHAR(64),
    product_name    VARCHAR(128),
    category_name   VARCHAR(64),
    channel         VARCHAR(32),
    province        VARCHAR(64),
    total_amount    DECIMAL(16,2),
    pay_status      VARCHAR(32),
    create_time     DATETIME
)
PRIMARY KEY (id)
DISTRIBUTED BY HASH(id) BUCKETS 3;

-- ==================== DWD 明细层 ====================
CREATE TABLE IF NOT EXISTS mall_dw.dwd_orders (
    order_id        VARCHAR(64),
    platform_code   VARCHAR(32),
    shop_id         VARCHAR(32),
    buyer_uid       VARCHAR(64),
    product_id      VARCHAR(64),
    product_name    VARCHAR(128),
    category_name   VARCHAR(64),
    channel         VARCHAR(32),
    province        VARCHAR(64),
    total_amount    DECIMAL(16,2),
    pay_status      VARCHAR(32),
    create_time     DATETIME,
    dt              VARCHAR(10)
)
PRIMARY KEY (order_id)
DISTRIBUTED BY HASH(order_id) BUCKETS 3;

-- ==================== DWS 汇总层 ====================
CREATE TABLE IF NOT EXISTS mall_dw.dws_channel_order_day (
    dt              VARCHAR(10),
    channel         VARCHAR(32),
    uv              BIGINT,
    order_cnt       BIGINT,
    paid_order_cnt  BIGINT,
    gmv             DECIMAL(16,2)
)
PRIMARY KEY (dt, channel)
DISTRIBUTED BY HASH(dt, channel) BUCKETS 1;

CREATE TABLE IF NOT EXISTS mall_dw.dws_province_order_day (
    dt              VARCHAR(10),
    province        VARCHAR(64),
    order_cnt       BIGINT,
    paid_order_cnt  BIGINT,
    paid_user_cnt   BIGINT,
    gmv             DECIMAL(16,2)
)
PRIMARY KEY (dt, province)
DISTRIBUTED BY HASH(dt, province) BUCKETS 1;

CREATE TABLE IF NOT EXISTS mall_dw.dws_product_sales_day (
    dt              VARCHAR(10),
    product_id      VARCHAR(64),
    product_name    VARCHAR(128),
    category_name   VARCHAR(64),
    sale_cnt        BIGINT,
    sale_amount     DECIMAL(16,2)
)
PRIMARY KEY (dt, product_id)
DISTRIBUTED BY HASH(dt, product_id) BUCKETS 1;

-- ==================== ADS 应用层 ====================
CREATE TABLE IF NOT EXISTS mall_dw.ads_trade_overview_di (
    dt               VARCHAR(10),
    total_order_cnt  BIGINT,
    paid_order_cnt   BIGINT,
    total_gmv        DECIMAL(16,2),
    paid_user_cnt    BIGINT
)
PRIMARY KEY (dt)
DISTRIBUTED BY HASH(dt) BUCKETS 1;

CREATE TABLE IF NOT EXISTS mall_dw.ads_channel_conversion_di (
    dt                    VARCHAR(10),
    channel               VARCHAR(32),
    visit_uv              BIGINT,
    order_cnt             BIGINT,
    paid_order_cnt        BIGINT,
    gmv                   DECIMAL(16,2),
    order_conversion_rate DECIMAL(16,4),
    pay_conversion_rate   DECIMAL(16,4)
)
PRIMARY KEY (dt, channel)
DISTRIBUTED BY HASH(dt, channel) BUCKETS 1;

CREATE TABLE IF NOT EXISTS mall_dw.ads_region_gmv_di (
    dt               VARCHAR(10),
    province         VARCHAR(64),
    order_cnt        BIGINT,
    paid_order_cnt   BIGINT,
    paid_user_cnt    BIGINT,
    gmv              DECIMAL(16,2)
)
PRIMARY KEY (dt, province)
DISTRIBUTED BY HASH(dt, province) BUCKETS 1;

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
