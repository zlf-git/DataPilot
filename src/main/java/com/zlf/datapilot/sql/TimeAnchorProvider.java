package com.zlf.datapilot.sql;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zlf.datapilot.config.DatapilotProperties;
import com.zlf.datapilot.knowledge.OntologyCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p>缓存：Caffeine <b>按表原子读</b>（{@code get(key, loader)}，同一表并发只查一次库），
 * TTL 由 {@code datapilot.engine.anchor-ttl-seconds} 配置，默认 60s。
 * 一次问数里 RecallStep 预热 + 生成器取用只查一次库（精确表述：<b>每张表每 TTL 最多查一次</b>）。
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
    private final int ttlSeconds;

    private final Cache<String, String> anchors;

    public TimeAnchorProvider(DatapilotProperties props, OntologyCache cache, EngineJdbc engineJdbc) {
        this.jdbc = engineJdbc.template();
        this.cache = cache;
        this.ttlSeconds = Math.max(1, props.getEngine().getAnchorTtlSeconds());
        this.anchors = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(64)
                .build();
        log.info("时间锚点缓存就绪：TTL {}s（datapilot.engine.anchor-ttl-seconds）", ttlSeconds);
    }

    /**
     * 取表的 dt 最大值（yyyy-MM-dd）。缺失/查询失败抛 IllegalArgumentException（上层转 errorMessage）。
     *
     * <p>用 {@code get(key, loader)} 而不是 {@code getIfPresent + put}：后者在冷缓存并发时
     * 每个线程都会真的查一次库（QA-04 P2-2 指出的非原子读）；Caffeine 的 {@code get} 对同一 key
     * 只让一个线程加载，其余等待结果。
     */
    public String anchor(String table) {
        if (table == null || !IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("非法表名，无法解析时间锚点：" + table);
        }
        if (!cache.tables().contains(table)) {
            throw new IllegalArgumentException("表不在白名单，无法解析时间锚点：" + table);
        }
        return anchors.get(table, this::loadAnchor);
    }

    /** 当前 TTL（秒），便于日志与文档核对 */
    public int ttlSeconds() {
        return ttlSeconds;
    }

    /**
     * 预热点（RecallStep 调用）：失败只记一行 warn 不阻断，真正需要锚点时由 {@link #anchor(String)} 显式报错。
     *
     * <p>同样走原子 {@code get(key, loader)}：预热与真正取用并发时，同一张表只会有一次查库。
     */
    public void prefetchQuietly(String table) {
        if (table == null || !IDENTIFIER.matcher(table).matches() || !cache.tables().contains(table)) {
            return;
        }
        try {
            anchors.get(table, this::loadAnchor);
        } catch (RuntimeException e) {
            log.warn("时间锚点预热失败（表 {}）：{}", table, e.getMessage());
        }
    }

    private String loadAnchor(String table) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT MAX(" + ANCHOR_FIELD + ") AS m FROM " + table);
        } catch (RuntimeException e) {
            // DB 原文（BadSqlGrammarException + SQL 片段 + 表名）只进日志，不进用户文案：
            // 口径同 QA-03 P1-2。这条在 Ticket 07 切 MySQL 引擎时必然踩到——
            // MySQL 演示库的 orders 没有 dt 字段，原文会把「SELECT MAX(dt) AS m FROM orders」直接怼给用户。
            log.warn("读取时间锚点失败 table={} err={}", table, brief(e));
            throw new IllegalArgumentException("表 " + table + " 读不到时间字段 " + ANCHOR_FIELD
                    + "（列不存在或引擎不可用），无法确定「近 N 天」窗口的结束日；"
                    + "请确认目标表带 " + ANCHOR_FIELD + " 字段且引擎可连");
        }
        Object v = rows.isEmpty() ? null : rows.get(0).get("m");
        LocalDate anchor = toLocalDate(v);
        if (anchor == null) {
            throw new IllegalArgumentException("表 " + table + " 的 dt 无数据，无法确定时间锚点（Ticket 04）");
        }
        return anchor.toString();
    }

    /** 异常摘要（不含堆栈）：类名 + 截断到 160 字的 message，只用于日志。 */
    private static String brief(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return t.getClass().getSimpleName();
        }
        return t.getClass().getSimpleName() + ": " + (msg.length() > 160 ? msg.substring(0, 160) + "..." : msg);
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
