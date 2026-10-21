package com.zlf.datapilot.sql;

import java.util.List;

/**
 * 受控 SQL 命令：SQL 文本 + 参数列表。
 * value 全部走 ? 占位符参数化绑定，不进 SQL 字符串，从根上消除注入。
 *
 * <p>{@code params} 允许传 null（调用方只想跑一条无参数 SQL 时容易这么写），
 * 紧凑构造器统一归一成空列表 —— 否则 {@code query(SqlCommand)} 里的
 * {@code params().toArray()} 会抛 NPE（QA-07 P2-4）。归一放在这里而不是执行器里：
 * 入口只有一处，将来接 DuckDB / Excel 执行器时不必各自再防一遍。
 */
public record SqlCommand(String sql, List<Object> params) {

    public SqlCommand {
        params = params == null ? List.of() : params;
    }

    public static SqlCommand of(String sql, List<Object> params) {
        return new SqlCommand(sql, params);
    }
}
