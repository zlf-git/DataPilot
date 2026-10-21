package com.zlf.datapilot.sql;

import java.util.List;

/**
 * 受控 SQL 命令：SQL 文本 + 参数列表。
 * value 全部走 ? 占位符参数化绑定，不进 SQL 字符串，从根上消除注入。
 */
public record SqlCommand(String sql, List<Object> params) {

    public static SqlCommand of(String sql, List<Object> params) {
        return new SqlCommand(sql, params);
    }
}
