package com.zlf.datapilot.sql;

import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * StarRocks 执行器：连接 {@code datapilot.engine.url}（mall_dw @9030），生产用只读账号 {@code agent_ro}。
 *
 * <p>装配条件：{@code datapilot.engine.type=starrocks}（缺省/留空也算，由 {@link EngineTypes} 归一）。
 * 与 {@link MysqlSqlExecutor} 互斥，容器里任何时候只有一个 {@link SqlExecutor}。
 *
 * <p>连接由 {@link EngineJdbc} 统一持有（与时间锚点查询共用同一个池），
 * 不再每次查询新建 TCP 连接（QA-04 P2-2）。
 */
@Component
@Conditional(OnEngineType.StarRocks.class)
public class StarRocksSqlExecutor extends JdbcSqlExecutor {

    /**
     * 声明给 Agent 读的表。
     *
     * <p><b>故意留空</b>（Ticket 19）：这里原本写死 {@code ads_* } 四张表，后果是**加一张表就要改代码重编译**——
     * 而「加表零改动」正是本项目声明的架构性质。留空后语义是「引擎不额外限制，可读集合完全由
     * semantic_model 决定」，物理上到底有没有这些表由 {@link com.zlf.datapilot.knowledge.SchemaDrift}
     * 查 information_schema 兜底（那个比静态清单准得多），闸门①的存在性/权限也仍然只认元数据白名单。
     *
     * <p>反过来若哪天确实要收紧（例如只开放 ads_ 前缀），在这里加回来也不会影响加表流程，
     * 只是自检会开始报 declaredNotInMetadata。
     */
    private static final List<String> DECLARED = List.of();

    public StarRocksSqlExecutor(EngineJdbc engineJdbc) {
        super(engineJdbc, DECLARED);
    }

    @Override
    public String engineName() {
        return "starrocks";
    }
}
