-- V21：商品指标别名补全（Ticket 21）
--
-- 背景：问「各品类<b>销量</b>占比与<b>金额</b>占比是否匹配」时，指标召回是字面匹配
-- （问题里是否出现指标名或别名），而「金额」既不是 sale_amount_product 的指标名
-- （商品销售额），也不在它的别名里（商品成交额、商品销售金额）→ 该指标压根没被召回，
-- 模型在 prompt 里看不到它，只能拿销量硬答。
--
-- 代码侧已加「同表补全」兜底（Recaller.withSiblingMetrics）：只要命中同表任一指标，
-- 该表其余指标一并召回。本迁移是另一层加固 —— 让「金额」这类通用说法能直接命中，
-- 不必依赖同表补全。两层都保留：补全负责覆盖「口语完全没提指标名」的情况，
-- 别名负责让命中的指标排序更靠前（命中项排在补全项之前）。
--
-- 幂等：可重复执行（先去掉可能已存在的同名别名再拼接，避免重复累加）。

UPDATE data_agent.metric_def
SET metric_alias = '商品成交额,商品销售金额,金额,销售额,商品金额,卖了多少金额'
WHERE metric_id = 'sale_amount_product';

UPDATE data_agent.metric_def
SET metric_alias = '销量,销售件数,售卖数量,卖了多少件,商品数量,件数'
WHERE metric_id = 'sale_cnt_product';
