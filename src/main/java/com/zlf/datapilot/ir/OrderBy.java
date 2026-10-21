package com.zlf.datapilot.ir;

/**
 * 排序项（Ticket 01 结构预留；别名排序由 Ticket 03 实现）。
 */
public class OrderBy {

    /** 排序字段（字段名或 metricId，由 Ticket 03 支持 metricId 别名） */
    private String field;
    /** 排序方向：asc / desc */
    private String direction = "asc";

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }
}
