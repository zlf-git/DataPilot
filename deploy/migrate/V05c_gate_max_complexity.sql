-- Ticket 15：代价闸门补 max_complexity 阈值（D2 复杂度公式补洞）
--
-- 复杂度分值 = max(1, 指标数) × max(1, 维度数) × max(1, join 数)，分值 ≥ 阈值即拒绝。
-- 三个 max(1,·) 是为了堵「任一因子为 0 → 乘积 0 → 阈值永不触发」：
-- 「10月19日当天总 GMV」这类单指标单表题维度=0、join=0，补洞前复杂度恒 0。
--
-- 已存在的库执行本脚本即可；新库走 seed.sql（自带该行）。脚本幂等，可重复执行。
-- 这里只 INSERT IGNORE、不做显式 UPDATE：max_join/max_metric 那种「seed 强制刷值」会让
-- DBA 手工调过的阈值被下次 seed 覆盖，max_complexity 不重复踩同一个坑。
INSERT IGNORE INTO gate_config (gate_name, param_key, param_value, updated_by)
VALUES ('cost', 'max_complexity', '3', 'Ticket 15');
