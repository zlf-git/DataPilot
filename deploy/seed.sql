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

-- 3. 指标口径（13 指标，修正后口径）
-- Ticket 05：metric_type=composite 的两种写法
--   ① 同表比值：metric_sql 直接写 A/B（如 pay_rate）→ 生成器输出 ROUND(A/B,4)
--   ② 跨表比值：metric_sql 只写分子，分母填 denominator_table + denominator_sql
--      （如 gmv_channel_ratio：分子=渠道表 SUM(gmv)，分母=总览表 SUM(total_gmv)）
INSERT INTO metric_def
(metric_id, metric_name, metric_alias, department, metric_sql, metric_type, table_name, dimensions, owner, status,
 denominator_table, denominator_sql) VALUES
('gmv_total',           '总GMV',       '总成交额,总交易额,总销售额', '电商部',   'SUM(total_gmv)',             'simple',    'ads_trade_overview_di',    'dt', '数据开发', '启用', NULL, NULL),
('gmv_channel',         '渠道GMV',     '渠道成交额,渠道交易额',     '渠道运营部','SUM(gmv)',                   'simple',    'ads_channel_conversion_di','dt,channel_name', '数据开发', '启用', NULL, NULL),
('gmv_region',          '地域GMV',     '区域成交额,省份成交额',     '电商部',   'SUM(gmv)',                   'simple',    'ads_region_gmv_di',       'dt,province_name,region', '数据开发', '启用', NULL, NULL),
('sale_amount_product', '商品销售额',   '商品成交额,商品销售金额',   '商品部',   'SUM(sale_amount)',           'simple',    'ads_product_sales_rank_di','dt,product_name,category_name', '数据开发', '启用', NULL, NULL),
('sale_cnt_product',    '商品销量',     '销量,销售件数,售卖数量,卖了多少件', '商品部', 'SUM(sale_cnt)',            'simple',    'ads_product_sales_rank_di','dt,product_name,category_name', '数据开发', '启用', NULL, NULL),
('order_cnt_total',     '总订单数',     '总订单量',                 '电商部',   'SUM(total_order_cnt)',       'simple',    'ads_trade_overview_di',    'dt', '数据开发', '启用', NULL, NULL),
('order_cnt_channel',   '渠道订单数',   '渠道订单量,渠道下单数',     '渠道运营部','SUM(order_cnt)',             'simple',    'ads_channel_conversion_di','dt,channel_name', '数据开发', '启用', NULL, NULL),
('order_cnt_region',    '地域订单数',   '地域订单量,省份订单量',     '电商部',   'SUM(order_cnt)',             'simple',    'ads_region_gmv_di',       'dt,province_name,region', '数据开发', '启用', NULL, NULL),
('paid_order_cnt',      '支付订单数',   '已支付订单,成交订单',       '电商部',   'SUM(paid_order_cnt)',        'simple',    'ads_trade_overview_di',    'dt', '数据开发', '启用', NULL, NULL),
('paid_user_cnt',       '支付用户数',   '付费用户数,成交用户数',     '电商部',   'SUM(paid_user_cnt)',         'uv',        'ads_trade_overview_di',    'dt', '数据开发', '启用', NULL, NULL),
('pay_rate',            '支付率',       '下单支付率,订单支付率',     '电商部',   'SUM(paid_order_cnt)/SUM(total_order_cnt)', 'composite', 'ads_trade_overview_di', 'dt', '数据开发', '启用', NULL, NULL),
('pay_conversion_rate', '支付转化率',   '下单转支付率,成交转化率',   '渠道运营部','SUM(paid_order_cnt)/SUM(visit_uv)', 'composite', 'ads_channel_conversion_di','dt,channel_name', '数据开发', '启用', NULL, NULL),
('gmv_channel_ratio',   '渠道GMV占比',  '渠道占比,GMV占比,渠道GMV占总GMV比例', '渠道运营部','SUM(gmv)',          'composite', 'ads_channel_conversion_di','dt,channel_name', '数据开发', '启用',
 'ads_trade_overview_di', 'SUM(total_gmv)')
-- 3b. 幂等且**收敛**（QA-05 P2-6）：改用 ON DUPLICATE KEY UPDATE，重跑 seed 会把 13 条指标的口径、
--     类型、主表、分母两列全部拉回基线；此前用 INSERT IGNORE，手工改坏的 gmv_channel_ratio 复位不了，
--     只有 pay_conversion_rate 因为额外写了 UPDATE 才能拉回。其余元数据表（semantic_model/field_metadata/
--     metric_table/dimension_def）仍只补缺不纠错——它们没有「口径被改坏」的同等风险，且不覆盖人工调整。
--     pay_conversion_rate 的口径裁定（访客→支付，见 docs/评测集.md §5）已内联在上面的 VALUES 里，
--     不再需要单独的 UPDATE。
ON DUPLICATE KEY UPDATE
    metric_name       = VALUES(metric_name),
    metric_alias      = VALUES(metric_alias),
    department        = VALUES(department),
    metric_sql        = VALUES(metric_sql),
    metric_type       = VALUES(metric_type),
    table_name        = VALUES(table_name),
    dimensions        = VALUES(dimensions),
    owner             = VALUES(owner),
    status            = VALUES(status),
    denominator_table = VALUES(denominator_table),
    denominator_sql   = VALUES(denominator_sql);


-- 4. 指标-表关联（多表指标在此展开，供存在性闸门查「指标在哪些表可算」）
INSERT IGNORE INTO metric_table (metric_id, table_name) VALUES
('gmv_total','ads_trade_overview_di'),
('gmv_channel','ads_channel_conversion_di'),
('gmv_region','ads_region_gmv_di'),
('sale_amount_product','ads_product_sales_rank_di'),
('sale_cnt_product','ads_product_sales_rank_di'),
('order_cnt_total','ads_trade_overview_di'),
('order_cnt_channel','ads_channel_conversion_di'),
('order_cnt_region','ads_region_gmv_di'),
('paid_order_cnt','ads_trade_overview_di'),
('paid_order_cnt','ads_channel_conversion_di'),
('paid_order_cnt','ads_region_gmv_di'),
('paid_user_cnt','ads_trade_overview_di'),
('paid_user_cnt','ads_region_gmv_di'),
('pay_rate','ads_trade_overview_di'),
('pay_conversion_rate','ads_channel_conversion_di'),
('gmv_channel_ratio','ads_channel_conversion_di');

-- 5. 维度字典（业务维度 → 字段映射）
-- B6：日期维度 dt 一对多——dt 在 4 张 ADS 表都有，不绑定单一表，避免含 dt 过滤的题被「维度越界」误拦
INSERT IGNORE INTO dimension_def (dim_name, field_name, table_name, dim_type, enum_values) VALUES
('渠道','channel_name','ads_channel_conversion_di','enum','["APP","小程序","网页"]'),
('渠道编码','channel','ads_channel_conversion_di','enum','["app","miniapp","web"]'),
('大区','region','ads_region_gmv_di','enum','["华东","华北","华南"]'),
('省份','province_name','ads_region_gmv_di','enum','["江苏","上海","浙江","北京","广东"]'),
('省份编码','province','ads_region_gmv_di','enum','["JS","SH","ZJ","BJ","GD"]'),
('类目','category_name','ads_product_sales_rank_di','enum','["办公家具","电脑外设"]'),
('商品','product_name','ads_product_sales_rank_di','enum','["机械键盘","4K显示器","人体工学椅"]'),
('商品编码','product_id','ads_product_sales_rank_di','enum',NULL),
('日期','dt','ads_trade_overview_di','time',NULL),
('日期','dt','ads_channel_conversion_di','time',NULL),
('日期','dt','ads_region_gmv_di','time',NULL),
('日期','dt','ads_product_sales_rank_di','time',NULL);

-- 6. 闸门配置（代价/结果自检默认阈值）
-- max_metric / max_join 按生成器当前真实能力配：单指标、单表（多指标在 Ticket 05 已明确不支持）。
-- 配成 3/2 会让阈值永远不可达 → 成了死配置，问题全部漏到生成器才报（QA-03 P2-5）。
-- 等 Ticket 05 落多指标、Ticket 15 落多表 join 时再调回 3/2。
INSERT IGNORE INTO gate_config (gate_name, param_key, param_value, updated_by) VALUES
('cost','max_join','1','数据开发'),
('cost','max_metric','1','数据开发'),
('cost','max_limit','1000','数据开发'),
('result','max_rows','1000','数据开发');
-- INSERT IGNORE 不更新既有行，显式 UPDATE 保证 seed 可重复执行
UPDATE gate_config SET param_value = '1' WHERE param_key = 'max_join';
UPDATE gate_config SET param_value = '1' WHERE param_key = 'max_metric';

-- 7. few-shot 示例（Ticket 03 任务 8：日期归一化，只说月日也要补年份）
-- few_shot_example 无唯一键，用 NOT EXISTS 保证 seed 可重复执行不重复插
INSERT INTO few_shot_example (question, `sql`, biz_domain)
SELECT '10月19日当天的总GMV是多少',
       '{"selectMode":"AGG","metrics":[{"metricId":"gmv_total"}],"filters":[{"field":"dt","op":"EQ","value":"2026-10-19"}]}',
       '交易'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM few_shot_example WHERE question = '10月19日当天的总GMV是多少');
