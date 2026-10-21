package com.zlf.datapilot.sql;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可插拔的 SQL 执行引擎接口（Ticket 07 接口化）。
 *
 * <p>借鉴 DataAgent 数据源管理思路：业务数据引擎可插拔，切换 MySQL / StarRocks
 * 只需换实现（{@code datapilot.engine.type}），Agent 主链路与五道闸门一行不改。
 * Excel 走 DuckDB 伪装成 SQL 引擎，同样实现本接口（数据源轴可插拔）。
 *
 * <p>实现约束：<b>任何实现都必须是只读语义</b>。IR → SqlCommand 只产出 SELECT，
 * 但接口本身不阻止有人手写 UPDATE，所以连接层再上一层保险：
 * 生产 StarRocks 用只读账号 {@code agent_ro}；MySQL 侧是演示用途（root/root123，见票面「不包含」）。
 */
public interface SqlExecutor {

    /** 引擎名：mysql / starrocks / excel。用于健康检查、日志与白名单钩子对齐。 */
    String engineName();

    /**
     * 列出当前引擎的可用表结构。
     * MySQL/StarRocks 查 information_schema；Excel 解析 sheet 名 + 表头。
     * 支撑元数据召回步骤：Agent 需要知道「这个数据源有哪些表、每张表有什么字段」。
     */
    List<TableMeta> listTables();

    /**
     * 执行只读查询，返回行集合。
     * <b>仅接口兼容用</b>，主链路一律走 {@link #query(SqlCommand)}：裸字符串入口无法保证参数化。
     */
    List<Map<String, Object>> query(String sql);

    /**
     * 参数化执行（IR 转出的 SqlCommand 走这里）。value 全部 {@code ?} 绑定，永不拼进 SQL 字符串。
     * 主链路唯一执行入口 —— {@link com.zlf.datapilot.agent.step.SqlExecuteStep} 只调这一个。
     */
    List<Map<String, Object>> query(SqlCommand cmd);

    /**
     * 引擎声明的<b>可读表白名单</b>钩子（Ticket 07 任务 4）。
     *
     * <p>语义：引擎自己知道「我这个数据源里哪些表是给 Agent 读的」，与元数据白名单
     * （{@code semantic_model}）取交集才是最终可读集合。空集合 = 不做额外限制（由元数据决定）。
     *
     * <p><b>当前只做「声明 + 一致性自检」</b>：由
     * {@code com.zlf.datapilot.knowledge.EngineWhitelist} 在启动与 {@code GET /api/health/engine}
     * 时计算交集并暴露差异，<b>不在闸门里拒绝</b>——拒绝判定归 Ticket 02 的存在性/权限闸门，
     * 接线点留在那边（本票按票面「实际注册逻辑在 Ticket 02 内完成」不越界）。
     */
    default Set<String> readableTables() {
        return Set.of();
    }
}
