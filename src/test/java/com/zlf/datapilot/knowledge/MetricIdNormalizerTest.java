package com.zlf.datapilot.knowledge;

import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.QueryIr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 指标 ID 词序归一（Ticket 20）。
 *
 * <p>这组用例盯的是「边界」而不是「能跑」：归一的策略刻意很窄，
 * 一旦有人把它放宽成编辑距离或前缀匹配，这里的第 3 条用例必须失败 ——
 * 那是把「模型没说」变成「系统替它编一个」，属于伪造语义。
 */
class MetricIdNormalizerTest {

    private static final Set<String> WHITELIST = new LinkedHashSet<>(List.of(
            "gmv_total", "gmv_region", "gmv_channel", "gmv_channel_ratio",
            "gmv_channel_daily_avg", "paid_order_cnt", "avg_order_value"));

    private MetricIdNormalizer normalizer() {
        return normalizer(WHITELIST);
    }

    private MetricIdNormalizer normalizer(Set<String> ids) {
        OntologyCache cache = mock(OntologyCache.class);
        when(cache.metricIds()).thenReturn(ids);
        return new MetricIdNormalizer(cache);
    }

    private QueryIr irOf(String... metricIds) {
        QueryIr ir = new QueryIr();
        List<MetricRef> refs = new ArrayList<>();
        for (String id : metricIds) {
            MetricRef r = new MetricRef();
            r.setMetricId(id);
            refs.add(r);
        }
        ir.setMetrics(refs);
        return ir;
    }

    @Test
    @DisplayName("已在白名单内：原样保留，不产生纠正记录")
    void keepsWhitelistedAsIs() {
        QueryIr ir = irOf("gmv_total", "paid_order_cnt");

        List<String> corrections = normalizer().normalize(ir);

        assertThat(corrections).isEmpty();
        assertThat(ir.getMetrics()).extracting(MetricRef::getMetricId)
                .containsExactly("gmv_total", "paid_order_cnt");
    }

    @Test
    @DisplayName("词序颠倒且词集唯一：纠正为白名单里的真 ID")
    void fixesReorderedWords() {
        // 真 LLM 实测的原样错误：daily 与 avg 顺序被写反
        QueryIr ir = irOf("gmv_channel_avg_daily");

        List<String> corrections = normalizer().normalize(ir);

        assertThat(ir.getMetrics().get(0).getMetricId()).isEqualTo("gmv_channel_daily_avg");
        assertThat(corrections).containsExactly("gmv_channel_avg_daily -> gmv_channel_daily_avg");
    }

    @Test
    @DisplayName("词形变体：daily 被写成 day 且词序也反了，仍纠正（截图里的真实错误）")
    void fixesWordFormVariant() {
        // 用户截图原样：近 7 天各渠道累计GMV / 占大盘比 / 日均产出 →
        // 模型输出 gmv_channel_avg_day，白名单里是 gmv_channel_daily_avg
        QueryIr ir = irOf("gmv_channel_avg_day");

        List<String> corrections = normalizer().normalize(ir);

        assertThat(ir.getMetrics().get(0).getMetricId()).isEqualTo("gmv_channel_daily_avg");
        assertThat(corrections).containsExactly("gmv_channel_avg_day -> gmv_channel_daily_avg");
    }

    @Test
    @DisplayName("跨概念词不合并：amount 不许被当成 gmv，sum 不许被当成 total")
    void doesNotMergeDifferentConcepts() {
        // 库里 total/sum、gmv/amount 都可能各有一个指标，合并就是静默错数
        QueryIr ir = irOf("gmv_channel_amount", "gmv_sum");

        List<String> corrections = normalizer().normalize(ir);

        assertThat(corrections).isEmpty();
        assertThat(ir.getMetrics()).extracting(MetricRef::getMetricId)
                .containsExactly("gmv_channel_amount", "gmv_sum");
    }

    @Test
    @DisplayName("词形归并后出现多候选：不猜，交回闸门拒绝")
    void ambiguousWordFormIsNotGuessed() {
        // 白名单里真有两个只差词序的合法指标时，系统没有依据选谁
        Set<String> both = new LinkedHashSet<>(List.of("gmv_daily_avg", "gmv_avg_day"));
        QueryIr ir = irOf("gmv_day_avg");

        List<String> corrections = normalizer(both).normalize(ir);

        assertThat(corrections).isEmpty();
        assertThat(ir.getMetrics().get(0).getMetricId()).isEqualTo("gmv_day_avg");
    }

    @Test
    @DisplayName("只写了 gmv：词集对不上任何 ID，不纠正（绝不猜成 gmv_total）")
    void doesNotGuessShortName() {
        QueryIr ir = irOf("gmv");

        List<String> corrections = normalizer().normalize(ir);

        assertThat(corrections).isEmpty();
        // 必须保持原样，交给存在性闸门拒绝 —— 猜成 gmv_total 或 gmv_channel 都是伪造语义
        assertThat(ir.getMetrics().get(0).getMetricId()).isEqualTo("gmv");
    }

    @Test
    @DisplayName("大小写与空白归一：GMV_Total 视同 gmv_total")
    void normalizesCaseAndWhitespace() {
        QueryIr ir = irOf("  GMV_Total  ");

        normalizer().normalize(ir);

        // 词集相等，唯一候选是 gmv_total
        assertThat(ir.getMetrics().get(0).getMetricId()).isEqualTo("gmv_total");
    }

    @Test
    @DisplayName("混合场景：只纠正能纠正的那个，其余保持原样")
    void fixesOnlyWhatItCan() {
        QueryIr ir = irOf("gmv_channel_avg_daily", "gmv", "paid_order_cnt");

        List<String> corrections = normalizer().normalize(ir);

        assertThat(corrections).hasSize(1);
        assertThat(ir.getMetrics()).extracting(MetricRef::getMetricId)
                .containsExactly("gmv_channel_daily_avg", "gmv", "paid_order_cnt");
    }

    @Test
    @DisplayName("空指标列表：安全返回，不抛异常")
    void handlesEmptyMetrics() {
        assertThat(normalizer().normalize(new QueryIr())).isEmpty();
        assertThat(normalizer().normalize(null)).isEmpty();
    }
}
