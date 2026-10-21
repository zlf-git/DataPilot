package com.zlf.datapilot.ir;

/**
 * 维度引用：指向白名单内的一个字段（如 channel_name / province_name）。
 */
public class DimensionRef {

    private String field;

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }
}
