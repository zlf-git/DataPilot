package com.zlf.datapilot.ir;

import java.util.ArrayList;
import java.util.List;

/**
 * 中间表示（IR）：LLM 生成的结构化查询意图，不吐裸 SQL。
 * 执行器只认落在 Ontology 白名单内的表/字段/指标，从根上堵幻觉与注入。
 *
 * <p>Ticket 01 基础结构：question + metrics + dimensions + filters + orderBy + limit，
 * 够单指标聚合 + 单表 + 等值过滤（Q1）。selectMode/timeRange/复合指标由 03/04/05 扩展。
 */
public class QueryIr {

    /** 原始问题（回显用） */
    private String question;

    /** 指标引用（聚合查询时非空；DETAIL 明细模式由 Ticket 03 引入后允许为空） */
    private List<MetricRef> metrics = new ArrayList<>();

    /** 维度引用（分组/明细维度） */
    private List<DimensionRef> dimensions = new ArrayList<>();

    /** 过滤条件（value 走参数化绑定） */
    private List<Filter> filters = new ArrayList<>();

    /** 排序（Ticket 01 结构预留，生成器暂不实现） */
    private List<OrderBy> orderBy = new ArrayList<>();

    /** 结果条数上限（空时由后续 ticket 注入 gate_config.max_rows 兜底） */
    private Integer limit;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
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
