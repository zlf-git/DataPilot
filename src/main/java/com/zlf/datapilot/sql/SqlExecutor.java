package com.zlf.datapilot.sql;

import java.util.List;
import java.util.Map;

/**
 * 可插拔的 SQL 执行引擎接口。
 * 借鉴 DataAgent 数据源管理思路：业务数据引擎可插拔，
 * 切换 MySQL / StarRocks 只需换实现，不改 Agent 主链路。
 */
public interface SqlExecutor {

    /** 引擎名：mysql / starrocks */
    String engineName();

    /** 执行只读查询，返回行集合 */
    List<Map<String, Object>> query(String sql);
}
