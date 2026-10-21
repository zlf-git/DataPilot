-- Ticket 05 补丁（QA-03 P2-4 / P2-5）：维度字典补齐 + 闸门阈值按真实能力收口
-- 幂等：可重复执行。目标库由调用方指定（不再硬编码，QA-05 P2-7）：
--   docker exec -i mysql-ai-analysis mysql -uroot -proot123 --default-character-set=utf8mb4 \
--     -D data_agent < deploy/migrate/V05b_dimension_and_thresholds.sql
--   不带 -D 执行会以 "No database selected" 失败，而不是悄悄作用到生产库。

-- 1) 维度字典补 3 个真维度（编码类字段），此前只有 6 个唯一字段，
--    DETAIL 落到 channel / province / product_id 就判「均不在维度字典」。
--    度量类字段（gmv/order_cnt/sale_cnt/各转化率…）是「度量」不是维度，故意不入字典。
INSERT IGNORE INTO dimension_def (dim_name, field_name, table_name, dim_type, enum_values) VALUES
('渠道编码','channel','ads_channel_conversion_di','enum','["app","miniapp","web"]'),
('省份编码','province','ads_region_gmv_di','enum','["JS","SH","ZJ","BJ","GD"]'),
('商品编码','product_id','ads_product_sales_rank_di','enum',NULL);

-- 2) 代价闸门阈值：max_metric 3 → 1、max_join 2 → 1
--    生成器当前只支持单指标、单表，阈值配 3/2 永远不可达，等于关掉闸门（QA-03 P2-5）。
UPDATE gate_config SET param_value = '1', updated_by = 'Ticket 05' WHERE param_key = 'max_metric';
UPDATE gate_config SET param_value = '1', updated_by = 'Ticket 05' WHERE param_key = 'max_join';

SELECT (SELECT COUNT(*) FROM dimension_def) AS dim_rows,
       (SELECT COUNT(DISTINCT field_name) FROM dimension_def) AS dim_fields,
       (SELECT GROUP_CONCAT(CONCAT(param_key, '=', param_value) ORDER BY param_key SEPARATOR ', ')
          FROM gate_config WHERE gate_name = 'cost') AS cost_thresholds;
