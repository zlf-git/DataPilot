-- ============================================================
-- Ticket 05 迁移：metric_def 增加跨表占比分母两列
-- 目标库：由调用方指定（**不再硬编码**，QA-05 P2-7）
--   容器 mysql-ai-analysis:3307。必须显式给库名，否则脚本以 "No database selected" 失败而不是
--   悄悄作用到生产库 data_agent：
--     docker exec -i mysql-ai-analysis mysql -uroot -proot123 --default-character-set=utf8mb4 \
--       -D data_agent < deploy/migrate/V05_metric_def_denominator.sql
--   隔离库/CI 上把 -D 换成目标库即可（QA-05 复现步骤里曾必须 sed 改库名才能安全执行）。
-- 幂等：先查 information_schema，已存在列则跳过（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）
-- 注意：本脚本只加列，**不回填口径**。老库跑完迁移后必须紧跟重跑 deploy/seed.sql，
--       否则 pay_conversion_rate 等仍是旧口径（isComposite 判为 false，生成器直接吐裸列名）。
-- ============================================================
SET NAMES utf8mb4;

SET @has_den_table := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'metric_def' AND COLUMN_NAME = 'denominator_table');
SET @sql := IF(@has_den_table = 0,
    'ALTER TABLE metric_def ADD COLUMN denominator_table VARCHAR(128) COMMENT ''跨表占比分母表（Ticket 05）'' AFTER table_name',
    'SELECT ''denominator_table 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_den_sql := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'metric_def' AND COLUMN_NAME = 'denominator_sql');
SET @sql := IF(@has_den_sql = 0,
    'ALTER TABLE metric_def ADD COLUMN denominator_sql VARCHAR(500) COMMENT ''分母口径SQL，必须自带聚合（Ticket 05）'' AFTER denominator_table',
    'SELECT ''denominator_sql 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 校验：两列都应在（顺带回显实际作用的库，避免「以为改了 A 库其实改了 B 库」）
SELECT DATABASE() AS applied_to, COLUMN_NAME, COLUMN_TYPE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'metric_def'
  AND COLUMN_NAME IN ('denominator_table', 'denominator_sql');
