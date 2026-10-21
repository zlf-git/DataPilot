package com.zlf.datapilot.sql;

import com.zlf.datapilot.config.DatapilotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Ticket 07 集成测试：切 {@code datapilot.engine.type} → 容器里生效的执行器跟着换，且能真的跑单表查询。
 *
 * <p>只测<b>外部行为</b>（票面要求）：
 * <ol>
 *   <li>容器里 {@link SqlExecutor} 只有一个实现，且是配置指定那个（{@code engineName()} 对得上）；</li>
 *   <li>{@code datapilot.engine.type} 写错 → 启动即失败，不留「两个执行器都没装配」的隐式坑；</li>
 *   <li>MySQL 侧对 {@code orders} 跑通单表查询（真实连库，端口不可达时自动 skip 而不是假绿）；</li>
 *   <li>StarRocks 侧对 {@code ads_trade_overview_di} 跑通单表查询（接口化不能把主链路改坏）。</li>
 * </ol>
 *
 * <p>用 {@link ApplicationContextRunner} 而不是 {@code @SpringBootTest}：本票要验的是「引擎这块装配对了没」，
 * 不需要拉起整个应用（更不需要元数据库）。{@link EngineJdbc} 带
 * {@code initializationFailTimeout(-1)}，开池不连库，所以没起容器也能装配成功。
 */
class EngineSwitchTest {

    private static final String MYSQL_URL =
            "jdbc:mysql://127.0.0.1:3307/data_agent?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String SR_URL =
            "jdbc:mysql://127.0.0.1:9030/mall_dw?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false";

    // ---------------- 装配（不需要任何数据库） ----------------

    private ApplicationContextRunner runner(String type) {
        DatapilotProperties props = new DatapilotProperties();
        props.getEngine().setType(type);
        if ("mysql".equals(type)) {
            props.getEngine().setUrl(MYSQL_URL);
            props.getEngine().setUsername("root");
            props.getEngine().setPassword("root123");
        } else {
            props.getEngine().setUrl(SR_URL);
            props.getEngine().setUsername("agent_ro");
            props.getEngine().setPassword("AgentRo@2026");
        }
        return new ApplicationContextRunner()
                .withBean("datapilotProperties", DatapilotProperties.class, () -> props)
                .withPropertyValues("datapilot.engine.type=" + type)
                .withUserConfiguration(EngineJdbc.class, StarRocksSqlExecutor.class, MysqlSqlExecutor.class);
    }

    @Test
    void defaultIsStarRocksAndItIsTheOnlyExecutor() {
        runner("starrocks").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(SqlExecutor.class)).hasSize(1);
            SqlExecutor ex = ctx.getBean(SqlExecutor.class);
            assertThat(ex.engineName()).isEqualTo("starrocks");
            assertThat(((JdbcSqlExecutor) ex).readableTables())
                    .containsExactlyInAnyOrder("ads_trade_overview_di", "ads_channel_conversion_di",
                            "ads_region_gmv_di", "ads_product_sales_rank_di");
            // 配置（properties）与条件（@ConditionalOnProperty）必须指向同一个引擎
            assertThat(ctx.getBean(EngineJdbc.class).engineType()).isEqualTo(ex.engineName());
        });
    }

    @Test
    void switchingTypeBringsMysqlExecutorIntoPlay() {
        runner("mysql").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(SqlExecutor.class)).hasSize(1);
            SqlExecutor ex = ctx.getBean(SqlExecutor.class);
            assertThat(ex).isInstanceOf(MysqlSqlExecutor.class);
            assertThat(ex.engineName()).isEqualTo("mysql");
            // MySQL 侧只声明订单表：同实例里的 metric_def / agent_run 等元数据表不该进白名单
            assertThat(ex.readableTables()).containsExactly("orders");
            assertThat(ctx.getBean(EngineJdbc.class).engineType()).isEqualTo("mysql");
        });
    }

    @Test
    void unknownTypeFailsFastInsteadOfRegisteringNothing() {
        runner("mysqll").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .hasStackTraceContaining("datapilot.engine.type=mysqll 不受支持");
        });
    }

    @Test
    void missingUrlFailsFast() {
        DatapilotProperties props = new DatapilotProperties();
        props.getEngine().setType("starrocks");
        props.getEngine().setUrl("  ");
        new ApplicationContextRunner()
                .withBean("datapilotProperties", DatapilotProperties.class, () -> props)
                .withPropertyValues("datapilot.engine.type=starrocks")
                .withUserConfiguration(EngineJdbc.class, StarRocksSqlExecutor.class, MysqlSqlExecutor.class)
                .run(ctx -> assertThat(ctx.getStartupFailure())
                        .hasStackTraceContaining("datapilot.engine.url 未配置"));
    }

    // ---------------- 真连库（不可达则 skip，绝不假绿） ----------------

    @Test
    void mysqlEngineRunsSingleTableQueryOnOrders() {
        assumeTrue(portOpen(3307), "MySQL 3307 不可达，跳过真连库断言（本机需 docker mysql-ai-analysis）");
        runner("mysql").run(ctx -> {
            SqlExecutor ex = ctx.getBean(SqlExecutor.class);
            List<Map<String, Object>> rows = ex.query(SqlCommand.of("SELECT COUNT(*) AS cnt FROM orders", List.of()));
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).containsKey("cnt");
            // 演示库 orders 是空表也照样算跑通：链路通、方言对、结果结构对
            assertThat(((Number) rows.get(0).get("cnt")).longValue()).isGreaterThanOrEqualTo(0L);
        });
    }

    @Test
    void mysqlEngineBindsParametersInsteadOfConcatenating() {
        assumeTrue(portOpen(3307), "MySQL 3307 不可达，跳过真连库断言");
        runner("mysql").run(ctx -> {
            SqlExecutor ex = ctx.getBean(SqlExecutor.class);
            List<Map<String, Object>> rows = ex.query(SqlCommand.of("SELECT ? AS v", List.of("bound")));
            assertThat(rows).hasSize(1);
            assertThat(String.valueOf(rows.get(0).get("v"))).isEqualTo("bound");
            // 同一实例的元数据表通过引擎也读得到（root 权限），但白名单钩子已声明不该给 Agent 用
            assertThat(ex.readableTables()).doesNotContain("metric_def");
        });
    }

    @Test
    void starRocksEngineStillRunsSingleTableQuery() {
        assumeTrue(portOpen(9030), "StarRocks 9030 不可达，跳过真连库断言（本机需 docker sr-ai-analysis）");
        runner("starrocks").run(ctx -> {
            SqlExecutor ex = ctx.getBean(SqlExecutor.class);
            List<Map<String, Object>> rows =
                    ex.query(SqlCommand.of("SELECT COUNT(*) AS cnt FROM ads_trade_overview_di", List.of()));
            assertThat(rows).hasSize(1);
            assertThat(((Number) rows.get(0).get("cnt")).longValue()).isEqualTo(7L);
        });
    }

    @Test
    void listTablesReadsInformationSchema() {
        assumeTrue(portOpen(3307), "MySQL 3307 不可达，跳过 information_schema 断言");
        runner("mysql").run(ctx -> {
            List<TableMeta> tables = ctx.getBean(SqlExecutor.class).listTables();
            assertThat(tables).extracting(TableMeta::getTableName).contains("orders", "metric_def");
            TableMeta orders = tables.stream().filter(t -> "orders".equals(t.getTableName())).findFirst().orElseThrow();
            assertThat(orders.getColumns()).contains("order_id", "channel", "amount", "order_time");
        });
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
