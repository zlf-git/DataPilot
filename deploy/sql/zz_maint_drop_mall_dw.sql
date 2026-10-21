-- 一次性维护脚本（P1-A 数据修复）：重建 Paimon 的 mall_dw 库
--
-- 背景：Flink 容器三次重建 + 作业反复重放，导致 ODS/DWD/DWS 里同一批 75 条订单被重复累加，
-- SR 的 GMV 从 53042.41 涨到 265212.05（正好 5 倍）。脏数据目录已 mv 到
-- /paimon_warehouse_bad_20261021/（没删），但 HMS 里的表记录还在，CREATE TABLE IF NOT EXISTS
-- 会跳过，作业写不进去 → 这里把库整体 DROP 掉，让 job2~job4 重新建表、从头跑一次。
--
-- 用法：docker cp 到 flink-jobmanager 容器后 ./bin/sql-client.sh -f /tmp/drop_mall_dw.sql

CREATE CATALOG IF NOT EXISTS paimon_catalog WITH (
    'type' = 'paimon',
    'warehouse' = 'hdfs://hadoop-namenode:9000/paimon_warehouse',
    'metastore' = 'hive',
    'uri' = 'thrift://hive-metastore:9083'
);

USE CATALOG paimon_catalog;

DROP DATABASE IF EXISTS mall_dw CASCADE;
