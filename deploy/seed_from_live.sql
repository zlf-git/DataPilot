-- ============================================================
-- DataPilot 元数据导出（由 qa/dump_metadata.py 生成，勿手工编辑）
-- 来源实例：http://127.0.0.1:18080
-- 规模：表 4 / 字段 28 / 指标 13 / 闸门阈值 5
-- 用法：mysql -h127.0.0.1 -P3307 -uroot -proot123 data_agent --default-character-set=utf8mb4 < 本文件
-- 幂等：semantic_model / field_metadata 用 INSERT IGNORE（不覆盖人工改过的注释），
--       metric_def / gate_config 用 ON DUPLICATE KEY UPDATE（口径以本文件为准）
-- ============================================================
SET NAMES utf8mb4;

-- 1. 语义模型（4 张表）
INSERT IGNORE INTO semantic_model (table_name, table_comment, biz_domain) VALUES
('ads_trade_overview_di', '交易总览（全局日汇总）', '交易'),
('ads_channel_conversion_di', '渠道转化（渠道日汇总）', '渠道'),
('ads_region_gmv_di', '地域GMV（省份日汇总）', '地域'),
('ads_product_sales_rank_di', '商品销售排行（商品日汇总）', '商品');

-- 2. 字段元数据（28 个字段）
INSERT IGNORE INTO field_metadata (table_name, field_name, field_type, field_comment, field_role, enum_values) VALUES
('ads_trade_overview_di', 'dt', 'date', NULL, NULL, NULL),
('ads_trade_overview_di', 'total_order_cnt', 'bigint', '总订单数/订单量', 'metric', NULL),
('ads_trade_overview_di', 'total_gmv', 'decimal', '总GMV/成交额/交易额', 'metric', NULL),
('ads_trade_overview_di', 'paid_user_cnt', 'bigint', '支付用户数/付费用户数', 'metric', NULL),
('ads_channel_conversion_di', 'dt', 'date', '日期/时间', 'dimension', NULL),
('ads_channel_conversion_di', 'channel', 'varchar', '渠道编码/渠道ID', 'dimension', NULL),
('ads_channel_conversion_di', 'channel_name', 'varchar', '渠道/渠道名称', 'dimension', '["APP", "小程序", "网页"]'),
('ads_channel_conversion_di', 'visit_uv', 'bigint', '访客数/UV/访问人数', 'metric', NULL),
('ads_channel_conversion_di', 'order_cnt', 'bigint', '订单数/下单数', 'metric', NULL),
('ads_channel_conversion_di', 'paid_order_cnt', 'bigint', '支付订单数/成交订单', 'metric', NULL),
('ads_channel_conversion_di', 'gmv', 'decimal', 'GMV/成交额/交易额', 'metric', NULL),
('ads_channel_conversion_di', 'order_conversion_rate', 'decimal', '下单转化率/访问转下单率', 'metric', NULL),
('ads_channel_conversion_di', 'pay_conversion_rate', 'decimal', '支付转化率/下单转支付率', 'metric', NULL),
('ads_region_gmv_di', 'dt', 'date', '日期/时间', 'dimension', NULL),
('ads_region_gmv_di', 'province', 'varchar', '省份编码/省份ID', 'dimension', NULL),
('ads_region_gmv_di', 'province_name', 'varchar', '省份/省份名称/地区', 'dimension', '["江苏", "上海", "浙江", "北京", "广东"]'),
('ads_region_gmv_di', 'region', 'varchar', '大区/区域/片区', 'dimension', '["华东", "华北", "华南"]'),
('ads_region_gmv_di', 'order_cnt', 'bigint', '订单数/下单数', 'metric', NULL),
('ads_region_gmv_di', 'paid_order_cnt', 'bigint', '支付订单数/成交订单', 'metric', NULL),
('ads_region_gmv_di', 'paid_user_cnt', 'bigint', '支付用户数/付费用户数', 'metric', NULL),
('ads_region_gmv_di', 'gmv', 'decimal', 'GMV/成交额/交易额', 'metric', NULL),
('ads_product_sales_rank_di', 'dt', 'date', '日期/时间', 'dimension', NULL),
('ads_product_sales_rank_di', 'product_id', 'varchar', '商品ID/商品编码', 'dimension', NULL),
('ads_product_sales_rank_di', 'product_name', 'varchar', '商品/商品名称/产品', 'dimension', '["机械键盘", "4K显示器", "人体工学椅"]'),
('ads_product_sales_rank_di', 'category_name', 'varchar', '类目/品类/分类', 'dimension', '["办公家具", "电脑外设"]'),
('ads_product_sales_rank_di', 'sale_cnt', 'bigint', '销量/销售件数/售卖数量', 'metric', NULL),
('ads_product_sales_rank_di', 'sale_amount', 'decimal', '销售额/销售金额/成交金额（含未支付明细）', 'metric', NULL),
('ads_trade_overview_di', 'paid_order_cnt', 'bigint', '支付订单数/成交订单', 'metric', NULL);

-- 3. 指标口径（13 个指标，全列覆盖）
INSERT INTO metric_def
(metric_id, metric_name, metric_alias, department, metric_sql, metric_type, table_name, dimensions, owner, status, denominator_table, denominator_sql) VALUES
('gmv_total', '总GMV', '总成交额,总交易额,总销售额', '电商部', 'SUM(total_gmv)', 'simple', 'ads_trade_overview_di', 'dt', '数据开发', '启用', NULL, NULL),
('gmv_channel', '渠道GMV', '渠道成交额,渠道交易额', '渠道运营部', 'SUM(gmv)', 'simple', 'ads_channel_conversion_di', 'dt,channel_name', '数据开发', '启用', NULL, NULL),
('gmv_region', '地域GMV', '区域成交额,省份成交额', '电商部', 'SUM(gmv)', 'simple', 'ads_region_gmv_di', 'dt,province_name,region', '数据开发', '启用', NULL, NULL),
('sale_amount_product', '商品销售额', '商品成交额,商品销售金额', '商品部', 'SUM(sale_amount)', 'simple', 'ads_product_sales_rank_di', 'dt,product_name,category_name', '数据开发', '启用', NULL, NULL),
('order_cnt_total', '总订单数', '总订单量', '电商部', 'SUM(total_order_cnt)', 'simple', 'ads_trade_overview_di', 'dt', '数据开发', '启用', NULL, NULL),
('order_cnt_channel', '渠道订单数', '渠道订单量,渠道下单数', '渠道运营部', 'SUM(order_cnt)', 'simple', 'ads_channel_conversion_di', 'dt,channel_name', '数据开发', '启用', NULL, NULL),
('order_cnt_region', '地域订单数', '地域订单量,省份订单量', '电商部', 'SUM(order_cnt)', 'simple', 'ads_region_gmv_di', 'dt,province_name,region', '数据开发', '启用', NULL, NULL),
('paid_order_cnt', '支付订单数', '已支付订单,成交订单', '电商部', 'SUM(paid_order_cnt)', 'simple', 'ads_trade_overview_di', 'dt', '数据开发', '启用', NULL, NULL),
('paid_user_cnt', '支付用户数', '付费用户数,成交用户数', '电商部', 'SUM(paid_user_cnt)', 'uv', 'ads_trade_overview_di', 'dt', '数据开发', '启用', NULL, NULL),
('pay_rate', '支付率', '下单支付率,订单支付率', '电商部', 'SUM(paid_order_cnt)/SUM(total_order_cnt)', 'composite', 'ads_trade_overview_di', 'dt', '数据开发', '启用', NULL, NULL),
('pay_conversion_rate', '支付转化率', '下单转支付率,成交转化率', '渠道运营部', 'SUM(paid_order_cnt)/SUM(visit_uv)', 'composite', 'ads_channel_conversion_di', 'dt,channel_name', '数据开发', '启用', NULL, NULL),
('sale_cnt_product', '商品销量', '销量,销售件数,售卖数量,卖了多少件', '商品部', 'SUM(sale_cnt)', 'simple', 'ads_product_sales_rank_di', 'dt,product_name,category_name', '数据开发', '启用', NULL, NULL),
('gmv_channel_ratio', '渠道GMV占比', '渠道占比,GMV占比,渠道GMV占总GMV比例', '渠道运营部', 'SUM(gmv)', 'composite', 'ads_channel_conversion_di', 'dt,channel_name', '数据开发', '启用', 'ads_trade_overview_di', 'SUM(total_gmv)')
ON DUPLICATE KEY UPDATE
    metric_name = VALUES(metric_name), metric_alias = VALUES(metric_alias),
    department = VALUES(department), metric_sql = VALUES(metric_sql),
    metric_type = VALUES(metric_type), table_name = VALUES(table_name),
    dimensions = VALUES(dimensions), owner = VALUES(owner), status = VALUES(status),
    denominator_table = VALUES(denominator_table), denominator_sql = VALUES(denominator_sql);

-- 4. 指标-表关联（从指标定义派生：主表 + 分母表）
INSERT IGNORE INTO metric_table (metric_id, table_name) VALUES
('gmv_total', 'ads_trade_overview_di'),
('gmv_channel', 'ads_channel_conversion_di'),
('gmv_region', 'ads_region_gmv_di'),
('sale_amount_product', 'ads_product_sales_rank_di'),
('order_cnt_total', 'ads_trade_overview_di'),
('order_cnt_channel', 'ads_channel_conversion_di'),
('order_cnt_region', 'ads_region_gmv_di'),
('paid_order_cnt', 'ads_trade_overview_di'),
('paid_user_cnt', 'ads_trade_overview_di'),
('pay_rate', 'ads_trade_overview_di'),
('pay_conversion_rate', 'ads_channel_conversion_di'),
('sale_cnt_product', 'ads_product_sales_rank_di'),
('gmv_channel_ratio', 'ads_channel_conversion_di'),
('gmv_channel_ratio', 'ads_trade_overview_di');

-- 5. 闸门阈值（5 条，全列覆盖）
INSERT INTO gate_config (gate_name, param_key, param_value, updated_by) VALUES
('cost', 'max_join', '1', 'Ticket 05'),
('cost', 'max_metric', '1', 'Ticket 05'),
('cost', 'max_limit', '1000', NULL),
('result', 'max_rows', '1000', NULL),
('cost', 'max_complexity', '3', 'Ticket 15 验收脚本')
ON DUPLICATE KEY UPDATE param_value = VALUES(param_value), updated_by = VALUES(updated_by);
