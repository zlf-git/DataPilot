package com.zlf.datapilot.sql;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MySQL 执行器（Ticket 07 任务 2）：连接 {@code datapilot.engine.url} 指向的 MySQL 库，
 * 演示「换配置即换引擎」——同一套 IR → SqlCommand → 参数化执行，不碰 Agent 主链路。
 *
 * <p>装配条件：{@code datapilot.engine.type=mysql}。
 *
 * <p><b>账号口径（票面「不包含」明确写死，别事后补承诺）</b>：MySQL 侧演示用元数据库同实例的
 * {@code root/root123} 读写账号，订单表 {@code orders} 就在 {@code data_agent} 库里。
 * 这是本机单机演示的取舍，<b>不承诺 MySQL 侧只读</b>；生产只读承诺只对 StarRocks
 * {@code agent_ro} 给出（应用层白名单 + DB 侧账号双保险）。
 *
 * <p>也因此 MySQL 侧只声明 {@code orders} 一张表进白名单钩子：
 * 同一实例里的 {@code metric_def}、{@code agent_run} 等元数据表<b>不该</b>被问数链路读到。
 */
@Component
@ConditionalOnProperty(prefix = "datapilot.engine", name = "type", havingValue = "mysql")
public class MysqlSqlExecutor extends JdbcSqlExecutor {

    /** 声明给 Agent 读的表（Ticket 07 钩子）。MySQL 侧只有订单表用于演示。 */
    private static final List<String> DECLARED = List.of("orders");

    public MysqlSqlExecutor(EngineJdbc engineJdbc) {
        super(engineJdbc, DECLARED);
    }

    @Override
    public String engineName() {
        return "mysql";
    }
}
