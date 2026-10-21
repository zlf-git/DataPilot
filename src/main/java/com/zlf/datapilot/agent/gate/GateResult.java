package com.zlf.datapilot.agent.gate;

/**
 * 闸门检查结果。
 * 前四道闸门（存在性/权限/代价/语法）不通过即 reject（拒绝执行）；
 * 结果自检通过但结果可疑时返回 ok + warning（不拒绝，只附加告警）。
 */
public class GateResult {

    private final boolean pass;
    private final String reason;
    private final String warning;

    private GateResult(boolean pass, String reason, String warning) {
        this.pass = pass;
        this.reason = reason;
        this.warning = warning;
    }

    public static GateResult ok() {
        return new GateResult(true, null, null);
    }

    /** 通过但附带告警（结果自检用，如空结果/异常值） */
    public static GateResult okWarning(String warning) {
        return new GateResult(true, null, warning);
    }

    public static GateResult reject(String reason) {
        return new GateResult(false, reason, null);
    }

    public boolean isPass() {
        return pass;
    }

    public String getReason() {
        return reason;
    }

    public String getWarning() {
        return warning;
    }
}
