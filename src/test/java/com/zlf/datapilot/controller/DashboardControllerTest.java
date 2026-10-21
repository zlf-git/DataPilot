package com.zlf.datapilot.controller;

import com.zlf.datapilot.config.SecurityConfig;
import com.zlf.datapilot.dashboard.DashboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 大屏接口的 HTTP 层测试（P1-A）。关心三件事：
 * <ol>
 *   <li>匿名能读（大屏机上没有人会去输账号密码）；</li>
 *   <li>本机来源的跨域放行（go-view 是 8080 上的独立静态站）；</li>
 *   <li>非本机来源不放行（收窄边界，别让公网站点也能拿数据）。</li>
 * </ol>
 */
@WebMvcTest(controllers = DashboardController.class)
@Import(SecurityConfig.class)
class DashboardControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DashboardService service;

    @Test
    @DisplayName("匿名可读 summary，字段原样透出")
    void anonymousCanReadSummary() throws Exception {
        when(service.summary(null)).thenReturn(Map.of(
                "dt", "2026-10-19", "totalGmv", 53042.41, "payRate", 0.9));

        mvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dt").value("2026-10-19"))
                .andExpect(jsonPath("$.totalGmv").value(53042.41));
    }

    @Test
    @DisplayName("days 参数透传到 service（大屏可切 7/30 天）")
    void daysPassedThrough() throws Exception {
        when(service.tradeTrend(30)).thenReturn(List.of());

        mvc.perform(get("/api/dashboard/trade-trend").param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(service).tradeTrend(30);
    }

    @Test
    @DisplayName("product 的 limit 参数透传，默认 10")
    void productLimitPassedThrough() throws Exception {
        when(service.product(null, 10)).thenReturn(List.of());
        mvc.perform(get("/api/dashboard/product")).andExpect(status().isOk());
        verify(service).product(null, 10);

        when(service.product(null, 3)).thenReturn(List.of());
        mvc.perform(get("/api/dashboard/product").param("limit", "3")).andExpect(status().isOk());
        verify(service).product(null, 3);
    }

    @Test
    @DisplayName("go-view 来源（localhost 任意端口）跨域放行")
    void corsAllowsLocalOrigin() throws Exception {
        when(service.summary(null)).thenReturn(Map.of("dt", "2026-10-19"));

        mvc.perform(get("/api/dashboard/summary").header("Origin", "http://localhost:8080"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8080"));
    }

    @Test
    @DisplayName("跨域预检 OPTIONS 放行，且只声明 GET")
    void preflightAllowsGetOnly() throws Exception {
        mvc.perform(options("/api/dashboard/summary")
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:8080"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET"));
    }

    @Test
    @DisplayName("外部站点来源被拒（403 Invalid CORS request），数据不外流")
    void corsRejectsForeignOrigin() throws Exception {
        mvc.perform(get("/api/dashboard/summary").header("Origin", "http://evil.example.com"))
                .andExpect(status().isForbidden());
    }
}
