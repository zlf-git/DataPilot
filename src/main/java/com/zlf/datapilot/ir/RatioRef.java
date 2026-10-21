package com.zlf.datapilot.ir;

/**
 * 占比语义声明：分子/分母各是一个指标（metricId）。
 * <p>作用：让「各渠道GMV」（{@code metrics=[gmv_channel]}）与「各渠道GMV占比」
 * （同 metrics + 本字段）在 IR 层可区分——否则两者逐字段同形，生成器无从判断，
 * 评测集 Q15 会从「显式拒绝」退化成「返回各渠道GMV」的静默错。
 *
 * <p>Ticket 03 只做声明与显式拒绝（占比计算未实现 → 抛 Ticket 05）；
 * 真正的计算（跨表分母/标量子查询/ROUND）由 Ticket 05 落地。
 */
public class RatioRef {

    /** 分子指标 metricId，如 gmv_channel */
    private String numerator;

    /** 分母指标 metricId，如 gmv_total */
    private String denominator;

    public String getNumerator() {
        return numerator;
    }

    public void setNumerator(String numerator) {
        this.numerator = numerator;
    }

    public String getDenominator() {
        return denominator;
    }

    public void setDenominator(String denominator) {
        this.denominator = denominator;
    }
}
