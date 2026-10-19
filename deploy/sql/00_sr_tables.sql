-- ============================================================
-- StarRocks 内表 DDL（mall_dw 库）
-- 架构定位：StarRocks 是 ADS 的唯一应用层存储（热查询）。Flink 流式消费 Paimon
--   DWS 的 changelog 直写这 4 张主键表（作业 05），全量重建走批作业 06。
--   DIM/DWD/DWS 三层只落 Paimon 湖（HDFS /paimon_warehouse），StarRocks 不落。
--   维度描述（channel_name / province_name / region / product_name / category_name）
--   已在 DWS 层 join 维度表拼好，随 ADS 冗余进 StarRocks，Agent 直接查中文名。
-- 主键表（PRIMARY KEY）承接 Flink 直写，实现 upsert 去重。
-- 关键配置：
--   1. dt 统一 DATE NOT NULL（主键列必须 NOT NULL）。
--   2. replication_num=1：集群默认 3，单 BE 环境建表会失败（报错不指向根因）。
--   3. enable_persistent_index=true：主键索引常驻内存，历史数据堆多会撑爆 BE，
--      开启后持久化索引落盘。
-- ============================================================

CREATE DATABASE IF NOT EXISTS mall_dw;

-- ==================== ADS 应用层（4 张，Flink 流写 + 批重建） ====================

-- 1. 经营总览（一年几千行，不分区）
CREATE TABLE IF NOT EXISTS mall_dw.ads_trade_overview_di (
    dt               DATE NOT NULL,
    total_order_cnt  BIGINT,
    paid_order_cnt   BIGINT,
    total_gmv        DECIMAL(16,2),
    paid_user_cnt    BIGINT
)
PRIMARY KEY (dt)
DISTRIBUTED BY HASH(dt) BUCKETS 1
PROPERTIES (
    "replication_num" = "1",
    "enable_persistent_index" = "true"
);

-- 2. 渠道转化（channel_name 为维表拼回的渠道中文名）
CREATE TABLE IF NOT EXISTS mall_dw.ads_channel_conversion_di (
    dt                    DATE NOT NULL,
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
DISTRIBUTED BY HASH(dt, channel) BUCKETS 1
PROPERTIES (
    "replication_num" = "1",
    "enable_persistent_index" = "true"
);

-- 3. 地区 GMV（province_name / region 为维表拼回的省份中文名与大区）
CREATE TABLE IF NOT EXISTS mall_dw.ads_region_gmv_di (
    dt               DATE NOT NULL,
    province         VARCHAR(32),
    province_name    VARCHAR(64),
    region           VARCHAR(32),
    order_cnt        BIGINT,
    paid_order_cnt   BIGINT,
    paid_user_cnt    BIGINT,
    gmv              DECIMAL(16,2)
)
PRIMARY KEY (dt, province)
DISTRIBUTED BY HASH(dt, province) BUCKETS 1
PROPERTIES (
    "replication_num" = "1",
    "enable_persistent_index" = "true"
);

-- 4. 商品排行（SKU 数 × 365 天，量级最大，加日分区）
CREATE TABLE IF NOT EXISTS mall_dw.ads_product_sales_rank_di (
    dt               DATE NOT NULL,
    product_id       VARCHAR(64),
    product_name     VARCHAR(128),
    category_name    VARCHAR(64),
    sale_cnt         BIGINT,
    sale_amount      DECIMAL(16,2)
)
PRIMARY KEY (dt, product_id)
PARTITION BY date_trunc('day', dt)
DISTRIBUTED BY HASH(dt, product_id) BUCKETS 3
PROPERTIES (
    "replication_num" = "1",
    "enable_persistent_index" = "true",
    "partition_live_number" = "365"
);
