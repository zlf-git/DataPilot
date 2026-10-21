package com.zlf.datapilot.sql;

import com.zlf.datapilot.config.DatapilotProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * StarRocks 执行器：用 JdbcTemplate 连业务库（datapilot.engine，agent_ro 只读账号 @9030）。
 * 主链路走 {@link #query(SqlCommand)} 参数化执行；{@link #query(String)} 仅接口兼容。
 */
@Component
public class StarRocksSqlExecutor implements SqlExecutor {

    private final JdbcTemplate jdbc;

    public StarRocksSqlExecutor(DatapilotProperties props) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(props.getEngine().getUrl());
        ds.setUsername(props.getEngine().getUsername());
        ds.setPassword(props.getEngine().getPassword());
        this.jdbc = new JdbcTemplate(ds);
    }

    @Override
    public String engineName() {
        return "starrocks";
    }

    @Override
    public List<TableMeta> listTables() {
        // Ticket 01 不实现（元数据召回走 MetadataRepository，不走这里）
        return List.of();
    }

    @Override
    public List<Map<String, Object>> query(String sql) {
        return jdbc.queryForList(sql);
    }

    /** 参数化执行（IR 转出的 SqlCommand 走这里，value 用 ? 占位符防注入） */
    public List<Map<String, Object>> query(SqlCommand cmd) {
        return jdbc.queryForList(cmd.sql(), cmd.params().toArray());
    }
}
