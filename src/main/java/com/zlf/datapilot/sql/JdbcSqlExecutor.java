package com.zlf.datapilot.sql;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JDBC 系执行器公共实现（StarRocks / MySQL 共用）。
 *
 * <p>两个引擎都是 MySQL 协议，差别只在连接串、账号与「引擎声明的可读表」，
 * 所以执行逻辑不该各写一遍——{@link #query(SqlCommand)} 里任何一处参数绑定写错都是注入口子。
 * 子类只负责三件事：{@link #engineName()}、{@link #readableTables()}、连接来源（由 {@link EngineJdbc} 持有）。
 */
public abstract class JdbcSqlExecutor implements SqlExecutor {

    private final JdbcTemplate jdbc;
    private final Set<String> readableTables;

    protected JdbcSqlExecutor(EngineJdbc engineJdbc, Collection<String> readableTables) {
        this.jdbc = engineJdbc.template();
        this.readableTables = new LinkedHashSet<>(readableTables == null ? List.of() : readableTables);
    }

    @Override
    public List<Map<String, Object>> query(String sql) {
        // 裸字符串入口只给运维/自检查用；主链路走 query(SqlCommand)
        return jdbc.queryForList(sql);
    }

    @Override
    public List<Map<String, Object>> query(SqlCommand cmd) {
        if (cmd == null) {
            throw new IllegalArgumentException("SqlCommand 为空");
        }
        return jdbc.queryForList(cmd.sql(), cmd.params().toArray());
    }

    @Override
    public Set<String> readableTables() {
        return Set.copyOf(readableTables);
    }

    /**
     * 从 information_schema 读当前库的表 + 字段 + 表注释。
     * 只读元信息，不改任何状态；引擎不可用时直接抛（由调用方决定降级，见 EngineWhitelist）。
     */
    @Override
    public List<TableMeta> listTables() {
        String sql = """
                SELECT c.TABLE_NAME AS t, c.COLUMN_NAME AS col, t.TABLE_COMMENT AS cmt
                  FROM information_schema.COLUMNS c
                  JOIN information_schema.TABLES t
                    ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
                 WHERE c.TABLE_SCHEMA = DATABASE()
                 ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION
                """;
        Map<String, TableMeta> byTable = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql)) {
            String table = String.valueOf(row.get("t"));
            TableMeta meta = byTable.computeIfAbsent(table,
                    k -> new TableMeta(k, row.get("cmt") == null ? null : String.valueOf(row.get("cmt"))));
            meta.getColumns().add(String.valueOf(row.get("col")));
        }
        return new ArrayList<>(byTable.values());
    }
}
