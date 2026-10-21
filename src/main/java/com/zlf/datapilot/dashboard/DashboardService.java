package com.zlf.datapilot.dashboard;

import com.zlf.datapilot.sql.EngineJdbc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大屏数据服务（P1-A）：把 StarRocks 的 4 张 ADS 表包装成大屏能直接吃的 JSON。
 *
 * <p>为什么单开一个服务、不复用问数链路：大屏是「固定问题的定时刷新」，查询是写死的常量 SQL，
 * 不经过 LLM、不经过闸门、不需要 NL2SQL 的解释与埋点能力。硬塞进 AgentEngine 只会让
 * 两条完全不同的关注点互相拖累（大屏挂了不该影响问数，反之亦然）。
 *
 * <p>安全边界：
 * <ul>
 *   <li>只读：全部是 {@code SELECT}，无入参拼接进 SQL 文本（{@code dt} 走 JDBC 占位符，{@code days}/{@code limit} 是 int）。</li>
 *   <li>只碰 ADS 层：SQL 里写死 {@code mall_dw.ads_*} 全限定名，不提供「任意表查询」入口。</li>
 *   <li>匿名可读：大屏通常挂在内网大屏机上，只读聚合数据；写接口仍然需要角色（见 SecurityConfig）。</li>
 * </ul>
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private static final String T_OVERVIEW = "mall_dw.ads_trade_overview_di";
    private static final String T_CHANNEL = "mall_dw.ads_channel_conversion_di";
    private static final String T_REGION = "mall_dw.ads_region_gmv_di";
    private static final String T_PRODUCT = "mall_dw.ads_product_sales_rank_di";

    private final EngineJdbc engine;

    public DashboardService(EngineJdbc engine) {
        this.engine = engine;
    }

    private JdbcTemplate jdbc() {
        return engine.template();
    }

    /** 最新业务日期；ADS 空表时返回 null（大屏显示「暂无数据」而不是报 500）。 */
    public String latestDt() {
        try {
            Date d = jdbc().queryForObject("SELECT MAX(dt) FROM " + T_OVERVIEW, Date.class);
            return d == null ? null : d.toLocalDate().toString();
        } catch (Exception e) {
            throw unavailable("取最新业务日期失败", e);
        }
    }

    /**
     * 顶部卡片：当日核心指标 + 环比。
     *
     * @param dt 业务日期（{@code yyyy-MM-dd}），为空取 ADS 里最新一天
     */
    public Map<String, Object> summary(String dt) {
        String day = resolveDt(dt);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dt", day);
        if (day == null) {
            out.put("empty", true);
            return out;
        }

        Map<String, Object> cur = queryOne("SELECT dt, total_order_cnt, paid_order_cnt, total_gmv, paid_user_cnt FROM "
                + T_OVERVIEW + " WHERE dt = ?", day);
        out.put("totalOrderCnt", longOf(cur, "total_order_cnt"));
        out.put("paidOrderCnt", longOf(cur, "paid_order_cnt"));
        out.put("totalGmv", decimalOf(cur, "total_gmv"));
        out.put("paidUserCnt", longOf(cur, "paid_user_cnt"));

        long order = longOf(cur, "total_order_cnt");
        long paid = longOf(cur, "paid_order_cnt");
        out.put("payRate", order == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(paid)
                .divide(BigDecimal.valueOf(order), 4, RoundingMode.HALF_UP));
        BigDecimal gmv = decimalOf(cur, "total_gmv");
        out.put("avgOrderValue", paid == 0 ? BigDecimal.ZERO
                : gmv.divide(BigDecimal.valueOf(paid), 2, RoundingMode.HALF_UP));

        // 环比：前一天没有数据时给 null，前端显示「—」而不是假的 0%
        Map<String, Object> prev = queryOne("SELECT dt, total_gmv, paid_order_cnt FROM " + T_OVERVIEW
                + " WHERE dt < ? ORDER BY dt DESC LIMIT 1", day);
        if (prev.isEmpty()) {
            out.put("prevDt", null);
            out.put("gmvDod", null);
            out.put("paidOrderDod", null);
            return out;
        }
        out.put("prevDt", dateStr(prev.get("dt")));
        out.put("gmvDod", dod(gmv, decimalOf(prev, "total_gmv")));
        out.put("paidOrderDod", dod(BigDecimal.valueOf(paid), BigDecimal.valueOf(longOf(prev, "paid_order_cnt"))));
        return out;
    }

    /** GMV / 支付订单趋势：最近 {@code days} 天，按日期升序（图表 X 轴顺序）。 */
    public List<Map<String, Object>> tradeTrend(int days) {
        int n = clamp(days, 1, 90);
        String sql = "SELECT dt, total_order_cnt, paid_order_cnt, total_gmv, paid_user_cnt FROM ("
                + "  SELECT dt, total_order_cnt, paid_order_cnt, total_gmv, paid_user_cnt FROM " + T_OVERVIEW
                + "  ORDER BY dt DESC LIMIT " + n
                + ") t ORDER BY dt ASC";
        List<Map<String, Object>> rows = query(sql);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dt", dateStr(r.get("dt")));
            m.put("totalGmv", decimalOf(r, "total_gmv"));
            m.put("totalOrderCnt", longOf(r, "total_order_cnt"));
            m.put("paidOrderCnt", longOf(r, "paid_order_cnt"));
            m.put("paidUserCnt", longOf(r, "paid_user_cnt"));
            out.add(m);
        }
        return out;
    }

    /** 渠道转化：当日各渠道访问 / 下单 / 支付 / GMV。 */
    public List<Map<String, Object>> channel(String dt) {
        String day = resolveDt(dt);
        if (day == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = query("SELECT channel, channel_name, visit_uv, order_cnt, paid_order_cnt,"
                + " gmv, order_conversion_rate, pay_conversion_rate FROM " + T_CHANNEL
                + " WHERE dt = ? ORDER BY gmv DESC", day);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channel", str(r.get("channel")));
            m.put("channelName", str(r.get("channel_name")));
            m.put("visitUv", longOf(r, "visit_uv"));
            m.put("orderCnt", longOf(r, "order_cnt"));
            m.put("paidOrderCnt", longOf(r, "paid_order_cnt"));
            m.put("gmv", decimalOf(r, "gmv"));
            m.put("orderConversionRate", decimalOf(r, "order_conversion_rate"));
            m.put("payConversionRate", decimalOf(r, "pay_conversion_rate"));
            out.add(m);
        }
        return out;
    }

    /** 地区 GMV：当日按省份，降序。地图/柱状图共用。 */
    public List<Map<String, Object>> region(String dt) {
        String day = resolveDt(dt);
        if (day == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = query("SELECT province, province_name, region, order_cnt, paid_order_cnt,"
                + " paid_user_cnt, gmv FROM " + T_REGION + " WHERE dt = ? ORDER BY gmv DESC", day);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("province", str(r.get("province")));
            m.put("provinceName", str(r.get("province_name")));
            m.put("region", str(r.get("region")));
            m.put("orderCnt", longOf(r, "order_cnt"));
            m.put("paidOrderCnt", longOf(r, "paid_order_cnt"));
            m.put("paidUserCnt", longOf(r, "paid_user_cnt"));
            m.put("gmv", decimalOf(r, "gmv"));
            out.add(m);
        }
        return out;
    }

    /** 商品销售排行：当日按销售额降序取前 {@code limit} 个。 */
    public List<Map<String, Object>> product(String dt, int limit) {
        String day = resolveDt(dt);
        if (day == null) {
            return List.of();
        }
        int n = clamp(limit, 1, 50);
        List<Map<String, Object>> rows = query("SELECT product_id, product_name, category_name, sale_cnt, sale_amount"
                + " FROM " + T_PRODUCT + " WHERE dt = ? ORDER BY sale_amount DESC LIMIT " + n, day);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", str(r.get("product_id")));
            m.put("productName", str(r.get("product_name")));
            m.put("categoryName", str(r.get("category_name")));
            m.put("saleCnt", longOf(r, "sale_cnt"));
            m.put("saleAmount", decimalOf(r, "sale_amount"));
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- 内部

    /** 显式日期优先；没传就取最新一天（大屏默认看「昨天/最新批」）。 */
    private String resolveDt(String dt) {
        if (dt != null && !dt.isBlank()) {
            String trimmed = dt.trim();
            try {
                return LocalDate.parse(trimmed).toString();
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dt 需为 yyyy-MM-dd 格式：" + trimmed);
            }
        }
        return latestDt();
    }

    private Map<String, Object> queryOne(String sql, Object... args) {
        List<Map<String, Object>> rows = query(sql, args);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private List<Map<String, Object>> query(String sql, Object... args) {
        try {
            return jdbc().queryForList(sql, args);
        } catch (Exception e) {
            throw unavailable("查询 ADS 失败", e);
        }
    }

    private ResponseStatusException unavailable(String msg, Exception e) {
        log.warn("{}：{}", msg, e.getMessage());
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, msg + "（StarRocks 可能未就绪）");
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 环比：(cur - prev) / prev，保留 4 位；prev 为 0 时返回 null（除零无意义，不编造 100%）。 */
    private static BigDecimal dod(BigDecimal cur, BigDecimal prev) {
        if (prev == null || prev.signum() == 0) {
            return null;
        }
        return cur.subtract(prev).divide(prev, 4, RoundingMode.HALF_UP);
    }

    private static String dateStr(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate ld) {
            return ld.toString();
        }
        if (v instanceof Date d) {
            return d.toLocalDate().toString();
        }
        String s = String.valueOf(v);
        return s.length() > 10 ? s.substring(0, 10) : s;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static long longOf(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static BigDecimal decimalOf(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(String.valueOf(v));
    }
}
