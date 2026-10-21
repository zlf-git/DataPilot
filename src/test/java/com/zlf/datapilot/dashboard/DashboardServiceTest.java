package com.zlf.datapilot.dashboard;

import com.zlf.datapilot.sql.EngineJdbc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 大屏数据服务的对外行为测试：只断言「返回给大屏的 JSON 长什么样」，
 * 不关心 SQL 怎么拼（SQL 是常量，真正要盯的是口径与字段名别改动）。
 */
class DashboardServiceTest {

    private final EngineJdbc engine = mock(EngineJdbc.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DashboardService service;

    DashboardServiceTest() {
        when(engine.template()).thenReturn(jdbc);
        service = new DashboardService(engine);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("summary：口径与环比都算对，日期原样回显")
    void summaryComputesRateAndDod() {
        when(jdbc.queryForList(contains("WHERE dt = ?"), any(Object[].class)))
                .thenReturn(List.of(row("dt", Date.valueOf("2026-10-19"),
                        "total_order_cnt", 10L, "paid_order_cnt", 9L,
                        "total_gmv", new BigDecimal("53042.41"), "paid_user_cnt", 9L)));
        when(jdbc.queryForList(contains("WHERE dt < ?"), any(Object[].class)))
                .thenReturn(List.of(row("dt", Date.valueOf("2026-10-18"),
                        "total_gmv", new BigDecimal("50579.92"), "paid_order_cnt", 10L)));

        Map<String, Object> r = service.summary("2026-10-19");

        assertThat(r.get("dt")).isEqualTo("2026-10-19");
        assertThat(r.get("totalGmv")).isEqualTo(new BigDecimal("53042.41"));
        assertThat(r.get("paidOrderCnt")).isEqualTo(9L);
        // 支付率 9/10
        assertThat(r.get("payRate")).isEqualTo(new BigDecimal("0.9000"));
        // 客单价 53042.41 / 9 = 5893.60
        assertThat(r.get("avgOrderValue")).isEqualTo(new BigDecimal("5893.60"));
        // 环比 (53042.41-50579.92)/50579.92 = 0.0487
        assertThat(r.get("gmvDod")).isEqualTo(new BigDecimal("0.0487"));
        // 支付订单 9 vs 10 → -10%
        assertThat(r.get("paidOrderDod")).isEqualTo(new BigDecimal("-0.1000"));
        assertThat(r.get("prevDt")).isEqualTo("2026-10-18");
    }

    @Test
    @DisplayName("summary：没有前一天数据时环比给 null，不编造 0% 或 100%")
    void summaryWithoutPrevDayGivesNullDod() {
        when(jdbc.queryForList(contains("WHERE dt = ?"), any(Object[].class)))
                .thenReturn(List.of(row("dt", Date.valueOf("2026-10-13"),
                        "total_order_cnt", 10L, "paid_order_cnt", 10L,
                        "total_gmv", new BigDecimal("58000.00"), "paid_user_cnt", 10L)));
        when(jdbc.queryForList(contains("WHERE dt < ?"), any(Object[].class)))
                .thenReturn(List.of());

        Map<String, Object> r = service.summary("2026-10-13");

        assertThat(r).containsEntry("prevDt", null).containsEntry("gmvDod", null)
                .containsEntry("paidOrderDod", null);
    }

    @Test
    @DisplayName("summary：dt 非法 → 400，不是 500")
    void summaryRejectsBadDt() {
        assertThatThrownBy(() -> service.summary("2026/10/19"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    @DisplayName("summary：传了不存在的日期 → empty 标记（大屏显示暂无数据）")
    void summaryOfMissingDay() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> r = service.summary("2026-01-01");

        assertThat(r.get("dt")).isEqualTo("2026-01-01");
        assertThat(r.get("totalGmv")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("trade-trend：返回按日期升序，字段名是前端约定的小驼峰")
    void tradeTrendMapsFields() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                row("dt", Date.valueOf("2026-10-18"), "total_order_cnt", 11L, "paid_order_cnt", 10L,
                        "total_gmv", new BigDecimal("50579.92"), "paid_user_cnt", 10L),
                row("dt", Date.valueOf("2026-10-19"), "total_order_cnt", 10L, "paid_order_cnt", 9L,
                        "total_gmv", new BigDecimal("53042.41"), "paid_user_cnt", 9L)));

        List<Map<String, Object>> list = service.tradeTrend(7);

        assertThat(list).hasSize(2);
        assertThat(list.get(0)).containsEntry("dt", "2026-10-18").containsEntry("totalGmv", new BigDecimal("50579.92"));
        assertThat(list.get(1)).containsEntry("dt", "2026-10-19");
        assertThat(list.get(0)).doesNotContainKey("total_gmv");
    }

    @Test
    @DisplayName("trade-trend：days 越界被夹到 1~90（避免大屏被传个 100000 拖库）")
    void tradeTrendClampsDays() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.tradeTrend(100000);
        verify(jdbc).queryForList(contains("LIMIT 90"), any(Object[].class));

        service.tradeTrend(0);
        verify(jdbc).queryForList(contains("LIMIT 1"), any(Object[].class));
    }

    @Test
    @DisplayName("channel：中文渠道名与两个转化率都在")
    void channelMapsFields() {
        when(jdbc.queryForList(contains("ads_channel_conversion_di"), any(Object[].class)))
                .thenReturn(List.of(row("channel", "miniapp", "channel_name", "小程序", "visit_uv", 30L,
                        "order_cnt", 3L, "paid_order_cnt", 3L, "gmv", new BigDecimal("15604.97"),
                        "order_conversion_rate", new BigDecimal("0.1000"),
                        "pay_conversion_rate", new BigDecimal("0.1000"))));

        List<Map<String, Object>> list = service.channel("2026-10-19");

        assertThat(list).hasSize(1);
        assertThat(list.get(0)).containsEntry("channelName", "小程序")
                .containsEntry("payConversionRate", new BigDecimal("0.1000"));
    }

    @Test
    @DisplayName("product：limit 越界被夹到 1~50")
    void productClampsLimit() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.product("2026-10-19", 999);
        verify(jdbc).queryForList(contains("LIMIT 50"), any(Object[].class));
    }

    @Test
    @DisplayName("StarRocks 挂了 → 503（大屏能显示「引擎未就绪」，不是 500 白屏）")
    void engineDownGives503() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("Communications link failure"));

        assertThatThrownBy(() -> service.tradeTrend(7))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("StarRocks");
    }

    @Test
    @DisplayName("ADS 空表：latestDt 为 null，各接口返回空而不是抛异常")
    void emptyAds() {
        when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(null);

        assertThat(service.latestDt()).isNull();
        assertThat(service.summary(null)).containsEntry("empty", true);
        assertThat(service.channel(null)).isEmpty();
        assertThat(service.region(null)).isEmpty();
        assertThat(service.product(null, 10)).isEmpty();
    }
}
