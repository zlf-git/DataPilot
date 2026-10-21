package com.zlf.datapilot.controller;

import com.zlf.datapilot.dashboard.DashboardService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 大屏数据接口（P1-A）：给 go-view 大屏供数，只读、只碰 ADS 层。
 *
 * <p>跨域：go-view 是独立部署的静态站（默认 {@code http://localhost:8080}），与后端不同源，
 * 所以这里显式放开本机来源的 GET。放开范围刻意收窄到「本机 + 只读」：
 * 公网来源仍会被浏览器拦，写接口也不受影响（写接口根本不在这条路径上）。
 *
 * <p>接口清单：
 * <ul>
 *   <li>{@code GET /api/dashboard/summary?dt=}：顶部卡片（GMV / 订单 / 支付 / 支付用户 / 支付率 / 客单价 / 环比）</li>
 *   <li>{@code GET /api/dashboard/trade-trend?days=7}：近 N 天趋势（按日期升序）</li>
 *   <li>{@code GET /api/dashboard/channel?dt=}：渠道转化</li>
 *   <li>{@code GET /api/dashboard/region?dt=}：地区 GMV</li>
 *   <li>{@code GET /api/dashboard/product?dt=&limit=10}：商品销售排行</li>
 * </ul>
 * 所有 {@code dt} 缺省时取 ADS 里最新一天，大屏不用自己做日期推算。
 */
@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(originPatterns = {"http://localhost:[*]", "http://127.0.0.1:[*]"}, maxAge = 600)
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(required = false) String dt) {
        return service.summary(dt);
    }

    @GetMapping("/trade-trend")
    public List<Map<String, Object>> tradeTrend(@RequestParam(defaultValue = "7") int days) {
        return service.tradeTrend(days);
    }

    @GetMapping("/channel")
    public List<Map<String, Object>> channel(@RequestParam(required = false) String dt) {
        return service.channel(dt);
    }

    @GetMapping("/region")
    public List<Map<String, Object>> region(@RequestParam(required = false) String dt) {
        return service.region(dt);
    }

    @GetMapping("/product")
    public List<Map<String, Object>> product(@RequestParam(required = false) String dt,
                                             @RequestParam(defaultValue = "10") int limit) {
        return service.product(dt, limit);
    }
}
