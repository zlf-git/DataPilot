package com.zlf.datapilot.ir;

/**
 * 时间窗口声明（Ticket 04）。
 *
 * <p>语义是「数据最新日往前 N 天」的相对窗口，<b>不用 BETWEEN</b>：
 * 生成器展开成半开区间 {@code dt > ? AND dt <= ?}，参数为 {@code anchor-days} 与 {@code anchor}。
 * BETWEEN 是闭区间，{@code BETWEEN '10-12' AND '10-19'} 会取 8 天，正是审计 P0#1 指出的口径错误。
 *
 * <p>{@code anchor} 是<b>解析结果</b>而不是模型输入：由数据 {@code MAX(dt)} 算出
 * （不是 {@code now()}，否则数据滞后时窗口整体漂移）。模型给了 anchor 时生成器会与
 * 数据最新日比对，不一致直接拒绝。
 */
public class TimeRange {

    /** 相对窗口类型：LAST_N_DAYS（近 N 天）。其余取值生成器显式拒绝，不做猜测 */
    private String relativeWindow;

    /** 窗口天数（含锚点当天），1~366 */
    private Integer days;

    /** 时间锚点 yyyy-MM-dd，由数据 MAX(dt) 解析后回填 */
    private String anchor;

    /**
     * 窗口平移天数（Ticket 10 环比用）：0/空 = 本期；N = 「前 N 天」那个窗口。
     *
     * <p>只由服务端构造（复制 IR 时填），模型不产出这个字段 —— 它是「同一份查询往前挪一个窗口」，
     * 不是用户可表达的时间语义，让 LLM 填等于给它一个能算错的口子。
     * 平移后区间仍是半开：{@code (anchor - offset - days, anchor - offset]}。
     */
    private Integer compareOffsetDays;

    public String getRelativeWindow() {
        return relativeWindow;
    }

    public void setRelativeWindow(String relativeWindow) {
        this.relativeWindow = relativeWindow;
    }

    public Integer getDays() {
        return days;
    }

    public void setDays(Integer days) {
        this.days = days;
    }

    public String getAnchor() {
        return anchor;
    }

    public void setAnchor(String anchor) {
        this.anchor = anchor;
    }

    public Integer getCompareOffsetDays() {
        return compareOffsetDays;
    }

    public void setCompareOffsetDays(Integer compareOffsetDays) {
        this.compareOffsetDays = compareOffsetDays;
    }

    @Override
    public String toString() {
        return "TimeRange{relativeWindow=" + relativeWindow + ", days=" + days + ", anchor=" + anchor
                + (compareOffsetDays == null ? "" : ", offset=" + compareOffsetDays) + "}";
    }
}
