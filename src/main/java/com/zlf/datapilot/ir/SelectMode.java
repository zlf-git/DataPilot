package com.zlf.datapilot.ir;

/**
 * 查询模式。
 * AGG：聚合（metrics 必填，dimensions 非空则 GROUP BY）；
 * DETAIL：明细/DISTINCT（metrics 必须为空，dimensions ≥ 1，生成 SELECT DISTINCT）。
 */
public enum SelectMode {
    AGG,
    DETAIL
}
