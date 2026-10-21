package com.zlf.datapilot.sql;

import com.zlf.datapilot.config.DatapilotProperties;
import com.zlf.datapilot.knowledge.OntologyCache;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.sql.SQLSyntaxErrorException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 时间锚点的失败口径（Ticket 07 补的用例，Ticket 04 当时没有单测）。
 *
 * <p>为什么票据 07 才补：切 MySQL 引擎后，演示库 {@code orders} 没有 {@code dt} 字段，
 * 「近 N 天」一启用就必然走到 {@code loadAnchor} 的 catch 分支。实测（18082，engine=mysql）输出的
 * 用户文案是 {@code 无法从表 orders 读取 MAX(dt)...：StatementCallback; bad SQL grammar
 * [SELECT MAX(dt) AS m FROM orders]} —— <b>DB 异常原文直接漏给最终用户</b>，
 * 与 QA-03 P1-2「只回 traceId、原文进日志」的口径相违背。本类把这条钉死。
 */
class TimeAnchorProviderTest {

    private static TimeAnchorProvider provider(JdbcTemplate jdbc, int ttl, String... tables) {
        DatapilotProperties props = new DatapilotProperties();
        props.getEngine().setAnchorTtlSeconds(ttl);
        EngineJdbc engineJdbc = mock(EngineJdbc.class);
        when(engineJdbc.template()).thenReturn(jdbc);
        OntologyCache cache = mock(OntologyCache.class);
        when(cache.tables()).thenReturn(Set.of(tables));
        return new TimeAnchorProvider(props, cache, engineJdbc);
    }

    @Test
    void returnsMaxDtAndCachesPerTable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT MAX(dt) AS m FROM ads_trade_overview_di"))
                .thenReturn(List.of(Map.of("m", Date.valueOf("2026-10-19"))));
        TimeAnchorProvider p = provider(jdbc, 60, "ads_trade_overview_di");

        assertThat(p.anchor("ads_trade_overview_di")).isEqualTo("2026-10-19");
        assertThat(p.anchor("ads_trade_overview_di")).isEqualTo("2026-10-19");
        // 同一表同一 TTL 内只查一次库（QA-04 P2-2 的原子缓存）
        verify(jdbc, times(1)).queryForList(anyString());
    }

    @Test
    void rejectsIllegalIdentifierBeforeTouchingDb() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TimeAnchorProvider p = provider(jdbc, 60, "orders");
        assertThatThrownBy(() -> p.anchor("orders; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法表名");
        verify(jdbc, times(0)).queryForList(anyString());
    }

    @Test
    void rejectsTableOutsideWhitelist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TimeAnchorProvider p = provider(jdbc, 60, "orders");
        assertThatThrownBy(() -> p.anchor("agent_run"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不在白名单");
        verify(jdbc, times(0)).queryForList(anyString());
    }

    @Test
    void dbErrorTextNeverReachesUserMessage() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        String dbText = "StatementCallback; bad SQL grammar [SELECT MAX(dt) AS m FROM orders]";
        when(jdbc.queryForList(eq("SELECT MAX(dt) AS m FROM orders"))).thenThrow(
                new BadSqlGrammarException("anchor", "SELECT MAX(dt) AS m FROM orders",
                        new SQLSyntaxErrorException("Unknown column 'dt' in 'field list'")));
        TimeAnchorProvider p = provider(jdbc, 60, "orders");

        assertThatThrownBy(() -> p.anchor("orders"))
                .isInstanceOf(IllegalArgumentException.class)
                // 有用的信息要在
                .hasMessageContaining("orders")
                .hasMessageContaining("读不到时间字段 dt")
                // DB 原文与 SQL 片段一个字都不能有
                .hasMessageNotContaining("SELECT")
                .hasMessageNotContaining("MAX")
                .hasMessageNotContaining("BadSqlGrammar")
                .hasMessageNotContaining(dbText);
    }

    @Test
    void emptyResultMeansNoDataInsteadOfFallbackToNow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT MAX(dt) AS m FROM ads_region_gmv_di")).thenReturn(List.of());
        TimeAnchorProvider p = provider(jdbc, 60, "ads_region_gmv_di");
        assertThatThrownBy(() -> p.anchor("ads_region_gmv_di"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dt 无数据");
    }

    @Test
    void nullMaxDtIsAlsoNoData() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT MAX(dt) AS m FROM ads_region_gmv_di"))
                .thenReturn(List.of(new java.util.HashMap<>(java.util.Collections.singletonMap("m", null))));
        TimeAnchorProvider p = provider(jdbc, 60, "ads_region_gmv_di");
        assertThatThrownBy(() -> p.anchor("ads_region_gmv_di"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dt 无数据");
    }

    @Test
    void prefetchSwallowsFailureButRealAnchorStillFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenThrow(new RuntimeException("connect refused"));
        TimeAnchorProvider p = provider(jdbc, 60, "orders");

        assertThatCode(() -> p.prefetchQuietly("orders")).doesNotThrowAnyException();
        assertThatThrownBy(() -> p.anchor("orders")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ttlComesFromConfiguration() {
        TimeAnchorProvider p = provider(mock(JdbcTemplate.class), 5, "orders");
        assertThat(p.ttlSeconds()).isEqualTo(5);
    }
}
