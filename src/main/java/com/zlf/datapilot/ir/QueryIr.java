package com.zlf.datapilot.ir;

import java.util.ArrayList;
import java.util.List;

/**
 * 中间表示（IR）：LLM 生成的结构化查询意图，不吐裸 SQL。
 * 执行器只认落在 Ontology 白名单内的表/字段/指标，从根上堵幻觉与注入。
 *
 * <p>Ticket 01 基础结构：question + metrics + dimensions + filters + orderBy + limit，
 * 够单指标聚合 + 单表 + 等值过滤（Q1）。selectMode/ratio 由 Ticket 03 引入，
 * timeRange 由 04 引入，复合指标计算由 05 引入。
 */
public class QueryIr {

    /** 原始问题（回显用） */
    private String question;

    /**
     * 查询模式：AGG（聚合，metrics 必填）/ DETAIL（明细 DISTINCT，metrics 必须为空）。
     * 空时按 metrics 推断（有指标=AGG，无指标=DETAIL），兼容 01c 期间的旧 stub。
     */
    private SelectMode selectMode;

    /** 指标引用（聚合查询时非空；DETAIL 明细模式必须为空） */
    private List<MetricRef> metrics = new ArrayList<>();

    /** 维度引用（AGG 时 GROUP BY，DETAIL 时 SELECT DISTINCT 的列） */
    private List<DimensionRef> dimensions = new ArrayList<>();

    /** 过滤条件（value 走参数化绑定） */
    private List<Filter> filters = new ArrayList<>();

    /** 排序（field 可为 metricId 或字段名；Ticket 03 支持按聚合别名排序） */
    private List<OrderBy> orderBy = new ArrayList<>();

    /** 结果条数上限（空时由生成器注入 gate_config.max_rows 兜底） */
    private Integer limit;

    /**
     * 占比语义声明（分子/分母指标）。仅在「A 占 B 的比例」类问题出现，
     * Ticket 03 遇到即显式拒绝，计算由 Ticket 05 落地。
     */
    private RatioRef ratio;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public SelectMode getSelectMode() {
        return selectMode;
    }

    public void setSelectMode(SelectMode selectMode) {
        this.selectMode = selectMode;
    }

    public RatioRef getRatio() {
        return ratio;
    }

    public void setRatio(RatioRef ratio) {
        this.ratio = ratio;
    }

    public List<MetricRef> getMetrics() {
        return metrics;
    }

    public void setMetrics(List<MetricRef> metrics) {
        this.metrics = metrics;
    }

    public List<DimensionRef> getDimensions() {
        return dimensions;
    }

    public void setDimensions(List<DimensionRef> dimensions) {
        this.dimensions = dimensions;
    }

    public List<Filter> getFilters() {
        return filters;
    }

    public void setFilters(List<Filter> filters) {
        this.filters = filters;
    }

    public List<OrderBy> getOrderBy() {
        return orderBy;
    }

    public void setOrderBy(List<OrderBy> orderBy) {
        this.orderBy = orderBy;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
