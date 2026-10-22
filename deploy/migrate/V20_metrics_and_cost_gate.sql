-- V20：多指标查询支持（Ticket 20）
--
-- 背景：切真实 LLM 后实测，复杂问数在三个地方卡住：
--   ① 指标清单缺「客单价 / 日均产出」，模型只能凭空造 ID，被存在性闸门整题拒绝；
--   ② 生成器硬拒「多指标」，「累计GMV + 占比 + 日均」这类正常问法无路可走；
--   ③ 代价闸门 max_metric=1 / max_complexity=3，即使放开多指标也会立刻被阈值拦住。
-- 本迁移一次解决 ①②③ 里属于「元数据」的部分，代码改动见同一提交的 Java 文件。
--
-- 幂等：可重复执行。

-- ==================== ① 补三个常用指标 ====================
--
-- 口径写法受 MetadataService.RATIO_PATTERN 约束：只允许「聚合()/聚合()」，
-- 聚合参数只允许字段名或 *，**不支持 COUNT(DISTINCT dt)**。
-- 因此「日均」写成 SUM(x)/COUNT(dt)：两张 ADS 表的主键都含 dt
-- （ads_trade_overview_di 主键是 dt；ads_channel_conversion_di 是 (dt,channel)），
-- 按 dt 唯一，COUNT(dt) 恒等于窗口内天数，与 COUNT(DISTINCT dt) 等价。

INSERT INTO data_agent.metric_def
  (metric_id, metric_name, metric_alias, department, metric_sql, metric_type,
   table_name, denominator_table, denominator_sql, dimensions, owner, status)
VALUES
  ('avg_order_value', '客单价', '客单价,平均订单金额,每单平均金额,平均客单', '电商部',
   'SUM(total_gmv)/SUM(paid_order_cnt)', 'composite',
   'ads_trade_overview_di', NULL, NULL, 'dt', '数据开发', '启用'),

  ('gmv_daily_avg', '日均GMV', '日均GMV,日均成交额,日均销售额,平均每天GMV', '电商部',
   'SUM(total_gmv)/COUNT(dt)', 'composite',
   'ads_trade_overview_di', NULL, NULL, 'dt', '数据开发', '启用'),

  ('gmv_channel_daily_avg', '渠道日均GMV', '渠道日均GMV,渠道日均产出,各渠道日均', '渠道运营部',
   'SUM(gmv)/COUNT(dt)', 'composite',
   'ads_channel_conversion_di', NULL, NULL, 'dt,channel_name', '数据开发', '启用')
ON DUPLICATE KEY UPDATE
  metric_name       = VALUES(metric_name),
  metric_alias      = VALUES(metric_alias),
  metric_sql        = VALUES(metric_sql),
  metric_type       = VALUES(metric_type),
  table_name        = VALUES(table_name),
  denominator_table = VALUES(denominator_table),
  denominator_sql   = VALUES(denominator_sql),
  dimensions        = VALUES(dimensions),
  status            = VALUES(status);

-- ==================== ② 放宽代价闸门阈值 ====================
--
-- 复杂度 = 指标数 × 维度数 × join数（各因子下限 1），判定是「≥ 阈值即拒」。
--   3 指标 × 1 维度 × 1 join = 3   → 原本正好被 max_complexity=3 拒掉
--   5 指标 × 1 维度 × 1 join = 5   → 允许（对应 MAX_METRICS=5）
--   3 指标 × 2 维度 × 2 join = 12  → 仍然拒绝（真正的组合爆炸还得拆）
-- 取 10 是这两者之间的分界：容纳多指标，同时守住多表 + 多维度的组合。

UPDATE data_agent.gate_config
   SET param_value = '5', updated_by = 'Ticket 20 多指标', update_time = NOW()
 WHERE gate_name = 'cost' AND param_key = 'max_metric';

UPDATE data_agent.gate_config
   SET param_value = '10', updated_by = 'Ticket 20 多指标', update_time = NOW()
 WHERE gate_name = 'cost' AND param_key = 'max_complexity';
