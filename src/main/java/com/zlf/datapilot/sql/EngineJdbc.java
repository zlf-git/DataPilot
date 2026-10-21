package com.zlf.datapilot.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zlf.datapilot.config.DatapilotProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 业务引擎侧的共享连接池（Ticket 04 补丁：QA-04 P2-2）。
 *
 * <p>此前 {@link StarRocksSqlExecutor} 与 {@link TimeAnchorProvider} 各自 new 一个
 * {@code DriverManagerDataSource}，<b>每条 SQL 都新建一条 TCP 连接</b>：
 * 一次冷问数 = 1 次执行 + 4 次锚点预热 = 5 条连接（QA-04 用 TCP 计数代理实测到）。
 * 合并成一个 Hikari 池后，锚点查询与业务查询复用同一批连接。
 *
 * <p>注意两个刻意的工程选择：
 * <ul>
 *   <li>Bean 类型是 {@code EngineJdbc} 而不是 {@code DataSource}：Spring Boot 的
 *       {@code DataSourceAutoConfiguration} 带 {@code @ConditionalOnMissingBean(DataSource.class)}，
 *       一旦容器里出现第二个 {@code DataSource} bean，元数据库那片自动装配会整体退让，
 *       {@code MetadataRepository} 会连到业务库上去。</li>
 *   <li>{@code initializationFailTimeout(-1)}：StarRocks 没起来时应用照常启动，
 *       由查询阶段显式报错（与「查不到锚点显式失败」一致），不在启动期抛异常拖住整条链路。</li>
 * </ul>
 */
@Component
public class EngineJdbc {

    private static final Logger log = LoggerFactory.getLogger(EngineJdbc.class);

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;

    public EngineJdbc(DatapilotProperties props) {
        DatapilotProperties.Engine engine = props.getEngine();
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("engine-" + engine.getType());
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        cfg.setJdbcUrl(engine.getUrl());
        cfg.setUsername(engine.getUsername());
        cfg.setPassword(engine.getPassword());
        cfg.setMaximumPoolSize(Math.max(2, engine.getPoolSize()));
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(5_000);
        cfg.setValidationTimeout(3_000);
        // 只读账号 + 只读连接：即使上游漏了校验，写语句也会在连接层被拒
        cfg.setReadOnly(true);
        cfg.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(cfg);
        this.jdbc = new JdbcTemplate(dataSource);
        log.info("引擎连接池就绪：{}（pool={}，max={}）", engine.getUrl(), cfg.getPoolName(), cfg.getMaximumPoolSize());
    }

    public JdbcTemplate template() {
        return jdbc;
    }

    @PreDestroy
    public void close() {
        dataSource.close();
    }
}
