-- ============================================================
-- DataPilot 元数据 seed（v2）
-- 数据源：StarRocks mall_dw 4 张 ADS 内表（真实表）
-- 口径已按审计 C4/C5 修正：
--   C4① 订单量拆 trade(SUM total_order_cnt) / channel / region(SUM order_cnt)
--   C4② 总GMV 加 SUM
--   C4③ 支付用户数标 uv（跨期不可加）
--   C5   商品销售额与总GMV 分名，不复用「GMV」词
-- ============================================================
SET NAMES utf8mb4;

-- 1. 语义模型（4 张 ADS 表）
INSERT IGNORE INTO semantic_model (table_name, table_comment, biz_domain) VALUES
('ads_trade_overview_di',   '交易总览（全局日汇总）', '交易'),
('ads_channel_conversion_di','渠道转化（渠道日汇总）', '渠道'),
('ads_region_gmv_di',       '地域GMV（省份日汇总）', '地域'),
('ads_product_sales_rank_di','商品销售排行（商品日汇总）','商品');

-- 2. 字段元数据（28 字段，含枚举值）
INSERT IGNORE INTO field_metadata (table_name, field_name, field_type, field_comment, field_role, enum_values) VALUES
-- ads_trade_overview_di
('ads_trade_overview_di','dt','date','日期/时间/当天','dimension',NULL),
('ads_trade_overview_di','total_order_cnt','bigint','总订单数/订单量','metric',NULL),
('ads_trade_overview_di','paid_order_cnt','bigint','支付订单数/成交订单','metric',NULL),
('ads_trade_overview_di','total_gmv','decimal','总GMV/成交额/交易额','metric',NULL),
('ads_trade_overview_di','paid_user_cnt','bigint','支付用户数/付费用户数','metric',NULL),
-- ads_channel_conversion_di
('ads_channel_conversion_di','dt','date','日期/时间','dimension',NULL),
('ads_channel_conversion_di','channel','varchar','渠道编码/渠道ID','dimension',NULL),
('ads_channel_conversion_di','channel_name','varchar','渠道/渠道名称','dimension','["APP","小程序","网页"]'),
('ads_channel_conversion_di','visit_uv','bigint','访客数/UV/访问人数','metric',NULL),
('ads_channel_conversion_di','order_cnt','bigint','订单数/下单数','metric',NULL),
('ads_channel_conversion_di','paid_order_cnt','bigint','支付订单数/成交订单','metric',NULL),
('ads_channel_conversion_di','gmv','decimal','GMV/成交额/交易额','metric',NULL),
('ads_channel_conversion_di','order_conversion_rate','decimal','下单转化率/访问转下单率','metric',NULL),
('ads_channel_conversion_di','pay_conversion_rate','decimal','支付转化率/下单转支付率','metric',NULL),
-- ads_region_gmv_di
('ads_region_gmv_di','dt','date','日期/时间','dimension',NULL),
('ads_region_gmv_di','province','varchar','省份编码/省份ID','dimension',NULL),
('ads_region_gmv_di','province_name','varchar','省份/省份名称/地区','dimension','["江苏","上海","浙江","北京","广东"]'),
('ads_region_gmv_di','region','varchar','大区/区域/片区','dimension','["华东","华北","华南"]'),
('ads_region_gmv_di','order_cnt','bigint','订单数/下单数','metric',NULL),
('ads_region_gmv_di','paid_order_cnt','bigint','支付订单数/成交订单','metric',NULL),
('ads_region_gmv_di','paid_user_cnt','bigint','支付用户数/付费用户数','metric',NULL),
('ads_region_gmv_di','gmv','decimal','GMV/成交额/交易额','metric',NULL),
-- ads_product_sales_rank_di
('ads_product_sales_rank_di','dt','date','日期/时间','dimension',NULL),
('ads_product_sales_rank_di','product_id','varchar','商品ID/商品编码','dimension',NULL),
('ads_product_sales_rank_di','product_name','varchar','商品/商品名称/产品','dimension','["机械键盘","4K显示器","人体工学椅"]'),
('ads_product_sales_rank_di','category_name','varchar','类目/品类/分类','dimension','["办公家具","电脑外设"]'),
('ads_product_sales_rank_di','sale_cnt','bigint','销量/销售件数/售卖数量','metric',NULL),
('ads_product_sales_rank_di','sale_amount','decimal','销售额/销售金额/成交金额（含未支付明细）','metric',NULL);

-- 3. 指标口径（11 指标，修正后口径）
INSERT IGNORE INTO metric_def
(metric_id, metric_name, metric_alias, department, metric_sql, metric_type, table_name, dimensions, owner, status) VALUES
('gmv_total',           '总GMV',       '总成交额,总交易额,总销售额', '电商部',   'SUM(total_gmv)',             'simple',    'ads_trade_overview_di',    'dt', '数据开发', '启用'),
('gmv_channel',         '渠道GMV',     '渠道成交额,渠道交易额',     '渠道运营部','SUM(gmv)',                   'simple',    'ads_channel_conversion_di','dt,channel_name', '数据开发', '启用'),
('gmv_region',          '地域GMV',     '区域成交额,省份成交额',     '电商部',   'SUM(gmv)',                   'simple',    'ads_region_gmv_di',       'dt,province_name,region', '数据开发', '启用'),
('sale_amount_product', '商品销售额',   '商品成交额,商品销售金额',   '商品部',   'SUM(sale_amount)',           'simple',    'ads_product_sales_rank_di','dt,product_name,category_name', '数据开发', '启用'),
('order_cnt_total',     '总订单数',     '总订单量',                 '电商部',   'SUM(total_order_cnt)',       'simple',    'ads_trade_overview_di',    'dt', '数据开发', '启用'),
('order_cnt_channel',   '渠道订单数',   '渠道订单量,渠道下单数',     '渠道运营部','SUM(order_cnt)',             'simple',    'ads_channel_conversion_di','dt,channel_name', '数据开发', '启用'),
('order_cnt_region',    '地域订单数',   '地域订单量,省份订单量',     '电商部',   'SUM(order_cnt)',             'simple',    'ads_region_gmv_di',       'dt,province_name,region', '数据开发', '启用'),
('paid_order_cnt',      '支付订单数',   '已支付订单,成交订单',       '电商部',   'SUM(paid_order_cnt)',        'simple',    'ads_trade_overview_di',    'dt', '数据开发', '启用'),
('paid_user_cnt',       '支付用户数',   '付费用户数,成交用户数',     '电商部',   'SUM(paid_user_cnt)',         'uv',        'ads_trade_overview_di',    'dt', '数据开发', '启用'),
('pay_rate',            '支付率',       '下单支付率,订单支付率',     '电商部',   'SUM(paid_order_cnt)/SUM(total_order_cnt)', 'composite', 'ads_trade_overview_di', 'dt', '数据开发', '启用'),
('pay_conversion_rate', '支付转化率',   '下单转支付率,成交转化率',   '渠道运营部','pay_conversion_rate',        'simple',    'ads_channel_conversion_di','dt,channel_name', '数据开发', '启用');

-- 4. 指标-表关联（多表指标在此展开，供存在性闸门查「指标在哪些表可算」）
INSERT IGNORE INTO metric_table (metric_id, table_name) VALUES
('gmv_total','ads_trade_overview_di'),
('gmv_channel','ads_channel_conversion_di'),
('gmv_region','ads_region_gmv_di'),
('sale_amount_product','ads_product_sales_rank_di'),
('order_cnt_total','ads_trade_overview_di'),
('order_cnt_channel','ads_channel_conversion_di'),
('order_cnt_region','ads_region_gmv_di'),
('paid_order_cnt','ads_trade_overview_di'),
('paid_order_cnt','ads_channel_conversion_di'),
('paid_order_cnt','ads_region_gmv_di'),
('paid_user_cnt','ads_trade_overview_di'),
('paid_user_cnt','ads_region_gmv_di'),
('pay_rate','ads_trade_overview_di'),
('pay_conversion_rate','ads_channel_conversion_di');

-- 5. 维度字典（业务维度 → 字段映射）
INSERT IGNORE INTO dimension_def (dim_name, field_name, table_name, dim_type, enum_values) VALUES
('渠道','channel_name','ads_channel_conversion_di','enum','["APP","小程序","网页"]'),
('大区','region','ads_region_gmv_di','enum','["华东","华北","华南"]'),
('省份','province_name','ads_region_gmv_di','enum','["江苏","上海","浙江","北京","广东"]'),
('类目','category_name','ads_product_sales_rank_di','enum','["办公家具","电脑外设"]'),
('商品','product_name','ads_product_sales_rank_di','enum','["机械键盘","4K显示器","人体工学椅"]'),
('日期','dt','ads_trade_overview_di','time',NULL);

-- 6. 闸门配置（代价/结果自检默认阈值）
INSERT IGNORE INTO gate_config (gate_name, param_key, param_value, updated_by) VALUES
('cost','max_join','2','数据开发'),
('cost','max_metric','3','数据开发'),
('cost','max_limit','1000','数据开发'),
('result','max_rows','1000','数据开发');
