-- 清理旧版 Paimon 表（重建维度建模：事实表 + 3 维度表 + 4 DWS + 4 ADS）
CREATE CATALOG IF NOT EXISTS paimon_catalog WITH (
    'type' = 'paimon',
    'warehouse' = 'hdfs://hadoop-namenode:9000/paimon_warehouse',
    'metastore' = 'hive',
    'uri' = 'thrift://hive-metastore:9083'
);
USE CATALOG paimon_catalog;
USE mall_dw;
DROP TABLE IF EXISTS ods_orders;
DROP TABLE IF EXISTS dwd_orders;
DROP TABLE IF EXISTS dim_product;
DROP TABLE IF EXISTS dim_channel;
DROP TABLE IF EXISTS dim_province;
DROP TABLE IF EXISTS dws_channel_order_day;
DROP TABLE IF EXISTS dws_province_order_day;
DROP TABLE IF EXISTS dws_product_sales_day;
DROP TABLE IF EXISTS dws_trade_overview_day;
DROP TABLE IF EXISTS ads_trade_overview_di;
DROP TABLE IF EXISTS ads_channel_conversion_di;
DROP TABLE IF EXISTS ads_region_gmv_di;
DROP TABLE IF EXISTS ads_product_sales_rank_di;
