-- ============================================================
-- Ticket 05 迁移：metric_def 增加跨表占比分母两列
-- 目标库：data_agent（MySQL 8，容器 mysql-ai-analysis:3307）
-- 幂等：先查 information_schema，已存在列则跳过（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）
-- 执行：docker exec -i mysql-ai-analysis mysql -uroot -proot123 \
--         --default-character-set=utf8mb4 < deploy/migrate/V05_metric_def_denominator.sql
-- ============================================================
SET NAMES utf8mb4;
USE data_agent;

SET @has_den_table := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'data_agent' AND TABLE_NAME = 'metric_def' AND COLUMN_NAME = 'denominator_table');
SET @sql := IF(@has_den_table = 0,
    'ALTER TABLE metric_def ADD COLUMN denominator_table VARCHAR(128) COMMENT ''跨表占比分母表（Ticket 05）'' AFTER table_name',
    'SELECT ''denominator_table 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_den_sql := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'data_agent' AND TABLE_NAME = 'metric_def' AND COLUMN_NAME = 'denominator_sql');
SET @sql := IF(@has_den_sql = 0,
    'ALTER TABLE metric_def ADD COLUMN denominator_sql VARCHAR(500) COMMENT ''分母口径SQL，必须自带聚合（Ticket 05）'' AFTER denominator_table',
    'SELECT ''denominator_sql 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 校验：两列都应在
SELECT COLUMN_NAME, COLUMN_TYPE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'data_agent' AND TABLE_NAME = 'metric_def'
  AND COLUMN_NAME IN ('denominator_table', 'denominator_sql');
