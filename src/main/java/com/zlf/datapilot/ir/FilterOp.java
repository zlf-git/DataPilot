package com.zlf.datapilot.ir;

/**
 * 过滤操作符。只留白名单内的安全 op，禁 BETWEEN（闭区间语义会引入边界坑，见 B2 半开区间）。
 */
public enum FilterOp {
    EQ,     // =
    IN,     // IN (...)
    LIKE,   // LIKE '%..%'
    GT,     // >
    GE,     // >=
    LT,     // <
    LE      // <=
}
