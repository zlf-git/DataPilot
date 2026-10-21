package com.zlf.datapilot.ir;

/**
 * 过滤条件：字段 + 操作符 + 值。value 走参数化绑定，不进 SQL 字符串。
 */
public class Filter {

    private String field;
    private FilterOp op;
    private Object value;

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public FilterOp getOp() {
        return op;
    }

    public void setOp(FilterOp op) {
        this.op = op;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }
}
