package com.zlf.datapilot.sql;

/**
 * SQL 校验结果。
 * SqlValidator 执行后产出：是否通过 + 原因 + 规范化后的 SQL（如自动补 LIMIT）。
 */
public class ValidationResult {

    /** 是否通过校验 */
    private boolean pass;

    /** 不通过原因（通过时为空） */
    private String reason;

    /** 规范化后的 SQL（如自动补了 LIMIT 的版本） */
    private String normalizedSql;

    public static ValidationResult ok(String normalizedSql) {
        ValidationResult r = new ValidationResult();
        r.pass = true;
        r.normalizedSql = normalizedSql;
        return r;
    }

    public static ValidationResult fail(String reason) {
        ValidationResult r = new ValidationResult();
        r.pass = false;
        r.reason = reason;
        return r;
    }

    public boolean isPass() {
        return pass;
    }

    public void setPass(boolean pass) {
        this.pass = pass;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getNormalizedSql() {
        return normalizedSql;
    }

    public void setNormalizedSql(String normalizedSql) {
        this.normalizedSql = normalizedSql;
    }
}
