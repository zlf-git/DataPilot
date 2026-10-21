package com.zlf.datapilot.sql;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zlf.datapilot.config.DatapilotProperties;
import com.zlf.datapilot.knowledge.OntologyCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 时间锚点提供者（Ticket 04）。
 *
 * <p>「近 7 天」的终点必须是<b>数据最新日 MAX(dt)</b>，不是 {@code now()}：
 * 数据滞后一天，用 now() 就会把窗口整体前移到「昨天~前天……」，7 天里最后一天可能是空分区，
 * 结果凭空少一天的 GMV。这也是工程上「口径锚定在数据而非挂钟」的通行做法。
 *
 * <p>缓存：Caffeine TTL 60s 按表缓存，一次问数里 RecallStep 预热 + 生成器取用只查一次库。
 * 查不到锚点时<b>显式失败</b>，绝不回退到 now()（回退即口径错误且无声）。
 */
@Component
public class TimeAnchorProvider {

    private static final Logger log = LoggerFactory.getLogger(TimeAnchorProvider.class);

    /** 表名格式白名单：字母/下划线开头，仅字母数字下划线（表名要直拼进 MAX(dt) 查询） */
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]*$");

    private static final String ANCHOR_FIELD = "dt";

    private final JdbcTemplate jdbc;
    private final OntologyCache cache;

    private final Cache<String, String> anchors = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(64)
            .build();

    public TimeAnchorProvider(DatapilotProperties props, OntologyCache cache) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(props.getEngine().getUrl());
        ds.setUsername(props.getEngine().getUsername());
        ds.setPassword(props.getEngine().getPassword());
        this.jdbc = new JdbcTemplate(ds);
        this.cache = cache;
    }

    /**
     * 取表的 dt 最大值（yyyy-MM-dd）。缺失/查询失败抛 IllegalArgumentException（上层转 errorMessage）。
     */
    public String anchor(String table) {
        if (table == null || !IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("非法表名，无法解析时间锚点：" + table);
        }
        if (!cache.tables().contains(table)) {
            throw new IllegalArgumentException("表不在白名单，无法解析时间锚点：" + table);
        }
        String cached = anchors.getIfPresent(table);
        if (cached != null) {
            return cached;
        }
        String value = loadAnchor(table);
        anchors.put(table, value);
        return value;
    }

    /**
     * 预热点（RecallStep 调用）：失败只记一行 warn 不阻断，真正需要锚点时由 {@link #anchor(String)} 显式报错。
     */
    public void prefetchQuietly(String table) {
        if (table == null || !IDENTIFIER.matcher(table).matches() || !cache.tables().contains(table)) {
            return;
        }
        if (anchors.getIfPresent(table) != null) {
            return;
        }
        try {
            anchors.put(table, loadAnchor(table));
        } catch (RuntimeException e) {
            log.warn("时间锚点预热失败（表 {}）：{}", table, e.getMessage());
        }
    }

    private String loadAnchor(String table) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT MAX(" + ANCHOR_FIELD + ") AS m FROM " + table);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "无法从表 " + table + " 读取 MAX(dt)（列不存在或引擎不可用）：" + e.getMessage());
        }
        Object v = rows.isEmpty() ? null : rows.get(0).get("m");
        LocalDate anchor = toLocalDate(v);
        if (anchor == null) {
            throw new IllegalArgumentException("表 " + table + " 的 dt 无数据，无法确定时间锚点（Ticket 04）");
        }
        return anchor.toString();
    }

    private LocalDate toLocalDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate ld) {
            return ld;
        }
        if (v instanceof java.sql.Date sd) {
            return sd.toLocalDate();
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
        }
        String s = String.valueOf(v).trim();
        if (s.length() >= 10) {
            try {
                return LocalDate.parse(s.substring(0, 10));
            } catch (RuntimeException ignore) {
                // 落到下面统一返回 null
            }
        }
        return null;
    }
}
