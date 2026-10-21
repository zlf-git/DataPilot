package com.zlf.datapilot.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zlf.datapilot.config.DatapilotProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 业务引擎侧的共享连接池（Ticket 04 补丁：QA-04 P2-2；Ticket 07 起按 {@code engine.type} 选驱动）。
 *
 * <p>此前 {@link StarRocksSqlExecutor} 与 {@link TimeAnchorProvider} 各自 new 一个
 * {@code DriverManagerDataSource}，<b>每条 SQL 都新建一条 TCP 连接</b>：
 * 一次冷问数 = 1 次执行 + 4 次锚点预热 = 5 条连接（QA-04 用 TCP 计数代理实测到）。
 * 合并成一个 Hikari 池后，锚点查询与业务查询复用同一批连接。
 *
 * <p>注意三个刻意的工程选择：
 * <ul>
 *   <li>Bean 类型是 {@code EngineJdbc} 而不是 {@code DataSource}：Spring Boot 的
 *       {@code DataSourceAutoConfiguration} 带 {@code @ConditionalOnMissingBean(DataSource.class)}，
 *       一旦容器里出现第二个 {@code DataSource} bean，元数据库那片自动装配会整体退让，
 *       {@code MetadataRepository} 会连到业务库上去。</li>
 *   <li>{@code initializationFailTimeout(-1)}：StarRocks/MySQL 没起来时应用照常启动，
 *       由查询阶段显式报错（与「查不到锚点显式失败」一致），不在启动期抛异常拖住整条链路。</li>
 *   <li>{@code engine.type} 写错（如 {@code mysqll}）<b>启动即失败</b>，不留「两个执行器都没装配 →
 *       运行到执行步骤才 NoSuchBeanDefinition」的隐式坑（Ticket 07）。</li>
 *   <li>{@code engine.type} <b>缺省/空值归一成 starrocks</b>，与 {@code matchIfMissing=true} 对齐
 *       （QA-07 P2-2：此前条件装 StarRocks、构造抛「不受支持」，两套默认值互相矛盾）。</li>
 * </ul>
 */
@Component
public class EngineJdbc {

    private static final Logger log = LoggerFactory.getLogger(EngineJdbc.class);


    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final String engineType;

    public EngineJdbc(DatapilotProperties props) {
        DatapilotProperties.Engine engine = props.getEngine();
        // 空值/空串/缺行 → starrocks，与装配条件 OnEngineType 共用同一个 normalize（QA-07 P2-2 / QA-08 P2-3）。
        // 两边各写一套的后果：留空时条件装不上执行器、这里却认为是 starrocks，启动失败且报错不提配置名。
        String raw = engine.getType();
        String type = EngineTypes.normalize(raw);
        if (!EngineTypes.SUPPORTED.contains(type)) {
            throw new IllegalStateException("datapilot.engine.type=" + raw
                    + " 不受支持，可选值：" + EngineTypes.SUPPORTED
                    + "（留空默认 " + EngineTypes.DEFAULT_TYPE + "；Ticket 07）");
        }
        if (engine.getUrl() == null || engine.getUrl().isBlank()) {
            throw new IllegalStateException("datapilot.engine.url 未配置（Ticket 07）");
        }
        this.engineType = type;

        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("engine-" + type);
        cfg.setDriverClassName(driverFor(type));
        cfg.setJdbcUrl(engine.getUrl());
        cfg.setUsername(engine.getUsername());
        cfg.setPassword(engine.getPassword());
        cfg.setMaximumPoolSize(Math.max(2, engine.getPoolSize()));
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(5_000);
        cfg.setValidationTimeout(3_000);
        // 只读连接：即使上游漏了校验，写语句也会在连接层被拒。
        // StarRocks 侧另有 DB 账号 agent_ro 兜底；MySQL 侧是演示用 root（票面不承诺只读），
        // 应用层仍开 readOnly 作为第二道保险。
        cfg.setReadOnly(true);
        cfg.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(cfg);
        this.jdbc = new JdbcTemplate(dataSource);
        log.info("引擎连接池就绪：type={} pool={} max={}（driver={}）",
                type, cfg.getPoolName(), cfg.getMaximumPoolSize(), cfg.getDriverClassName());
    }

    private static String driverFor(String type) {
        return switch (type) {
            case "starrocks", "mysql" -> "com.mysql.cj.jdbc.Driver";
            default -> throw new IllegalStateException("无驱动映射：" + type);
        };
    }

    public JdbcTemplate template() {
        return jdbc;
    }

    /** 实际生效的引擎类型（与 {@code datapilot.engine.type} 一致，已 trim + 小写）。 */
    public String engineType() {
        return engineType;
    }

    @PreDestroy
    public void close() {
        dataSource.close();
    }
}
