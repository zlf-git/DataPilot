package com.zlf.datapilot.sql;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * StarRocks 执行器：连接 {@code datapilot.engine.url}（mall_dw @9030），生产用只读账号 {@code agent_ro}。
 *
 * <p>装配条件：{@code datapilot.engine.type=starrocks}（缺省即 starrocks，见 application.yml）。
 * 与 {@link MysqlSqlExecutor} 互斥，容器里任何时候只有一个 {@link SqlExecutor}。
 *
 * <p>连接由 {@link EngineJdbc} 统一持有（与时间锚点查询共用同一个池），
 * 不再每次查询新建 TCP 连接（QA-04 P2-2）。
 */
@Component
@ConditionalOnProperty(prefix = "datapilot.engine", name = "type", havingValue = "starrocks", matchIfMissing = true)
public class StarRocksSqlExecutor extends JdbcSqlExecutor {

    /** 声明给 Agent 读的 ADS 表（Ticket 07 钩子）。与 semantic_model 取交集后才生效，见 EngineWhitelist。 */
    private static final List<String> DECLARED = List.of(
            "ads_trade_overview_di",
            "ads_channel_conversion_di",
            "ads_region_gmv_di",
            "ads_product_sales_rank_di");

    public StarRocksSqlExecutor(EngineJdbc engineJdbc) {
        super(engineJdbc, DECLARED);
    }

    @Override
    public String engineName() {
        return "starrocks";
    }
}
