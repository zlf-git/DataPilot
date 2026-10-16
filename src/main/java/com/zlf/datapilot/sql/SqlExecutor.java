package com.zlf.datapilot.sql;

import java.util.List;
import java.util.Map;

/**
 * 可插拔的 SQL 执行引擎接口。
 * 借鉴 DataAgent 数据源管理思路：业务数据引擎可插拔，
 * 切换 MySQL / StarRocks 只需换实现，不改 Agent 主链路。
 * Excel 通过 DuckDB 伪装成 SQL 引擎，同样实现本接口（数据源轴可插拔）。
 */
public interface SqlExecutor {

    /** 引擎名：mysql / starrocks / excel */
    String engineName();

    /**
     * 列出当前引擎的可用表结构。
     * MySQL/StarRocks 查 information_schema；Excel 解析 sheet 名 + 表头。
     * 支撑元数据召回步骤：Agent 需要知道「这个数据源有哪些表、每张表有什么字段」。
     */
    List<TableMeta> listTables();

    /** 执行只读查询，返回行集合 */
    List<Map<String, Object>> query(String sql);
}
