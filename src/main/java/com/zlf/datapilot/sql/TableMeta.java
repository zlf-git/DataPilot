package com.zlf.datapilot.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * 表元数据结构：引擎返回的一张表的表名 + 注释 + 字段列表。
 * 供 SqlExecutor.listTables() 返回，支撑元数据召回与语义模型同步。
 */
public class TableMeta {

    private String tableName;

    private String tableComment;

    private List<String> columns = new ArrayList<>();

    public TableMeta() {
    }

    public TableMeta(String tableName, String tableComment) {
        this.tableName = tableName;
        this.tableComment = tableComment;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getTableComment() {
        return tableComment;
    }

    public void setTableComment(String tableComment) {
        this.tableComment = tableComment;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }
}
