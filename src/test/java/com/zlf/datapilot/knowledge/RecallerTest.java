package com.zlf.datapilot.knowledge;

import com.zlf.datapilot.knowledge.model.MetricDef;
import com.zlf.datapilot.knowledge.model.RecalledMetadata;
import com.zlf.datapilot.llm.EmbeddingClient;
import com.zlf.datapilot.vector.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 指标召回单测（Ticket 21）。
 *
 * <p>核心场景来自一次真实失败：问「各品类<b>销量</b>占比与<b>金额</b>占比是否匹配」，
 * 字面只命中「商品销量」，同表的「商品销售额」因问题里没出现「销售额」而漏召回，
 * 模型在 prompt 里看不到它 → 只能答销量、答不了金额。
 *
 * <p>这里钉住的就是「表已召回，表上的指标就该都是候选」这条规则，
 * 同时钉住它的边界：<b>不许跨表补全</b> —— 补到别的表会让代价闸门以「跨表指标」拒绝整题。
 */
class RecallerTest {

    private MetadataRepository repo;
    private Recaller recaller;

    private static MetricDef metric(String id, String name, String alias, String table) {
        MetricDef d = new MetricDef();
        d.setMetricId(id);
        d.setMetricName(name);
        d.setMetricAlias(alias);
        d.setTableName(table);
        d.setMetricSql("SUM(x)");
        return d;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(MetadataRepository.class);
        when(repo.listTables()).thenReturn(List.of());
        when(repo.listTerms()).thenReturn(List.of());
        when(repo.listFields(any())).thenReturn(List.of());
        when(repo.listFewShots(any())).thenReturn(List.of());
        when(repo.listMetrics()).thenReturn(List.of(
                metric("sale_cnt_product", "商品销量", "销量,件数", "ads_product_sales_rank_di"),
                metric("sale_amount_product", "商品销售额", "商品成交额,金额", "ads_product_sales_rank_di"),
                metric("gmv_total", "总GMV", "总成交额", "ads_trade_overview_di"),
                metric("paid_order_cnt", "支付订单数", "已支付订单", "ads_trade_overview_di")));

        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        recaller = new Recaller(repo, mock(EmbeddingClient.class), provider);
    }

    private List<String> recalledIds(RecalledMetadata meta) {
        return meta.getMetrics().stream().map(MetricDef::getMetricId).toList();
    }

    @Test
    @DisplayName("命中同表一个指标 → 该表其余指标一并召回（金额不再被漏掉）")
    void siblingMetricsAreRecalled() {
        RecalledMetadata meta = recaller.recall("各商品品类销量占比与金额占比是否匹配");

        assertThat(recalledIds(meta))
                .contains("sale_cnt_product", "sale_amount_product");
    }

    @Test
    @DisplayName("不跨表补全：命中商品表不能把总览表的指标也带进来")
    void siblingRecallDoesNotCrossTables() {
        RecalledMetadata meta = recaller.recall("各商品品类销量是多少");

        assertThat(recalledIds(meta)).containsExactlyInAnyOrder(
                "sale_cnt_product", "sale_amount_product");
        assertThat(recalledIds(meta)).doesNotContain("gmv_total", "paid_order_cnt");
    }

    @Test
    @DisplayName("命中多张表各自的指标时，两张表的指标都补全，且不重复")
    void multipleTablesAllCompleted() {
        RecalledMetadata meta = recaller.recall("销量和总GMV分别是多少");

        assertThat(recalledIds(meta)).containsExactlyInAnyOrder(
                "sale_cnt_product", "sale_amount_product", "gmv_total", "paid_order_cnt");
    }

    @Test
    @DisplayName("一个指标都没命中 → 不补全（补全只在「已知相关表」的基础上做）")
    void noHitMeansNoCompletion() {
        RecalledMetadata meta = recaller.recall("今天天气怎么样");

        assertThat(meta.getMetrics()).isEmpty();
    }

    @Test
    @DisplayName("别名也能命中（「金额」直接落到商品销售额）")
    void aliasHits() {
        RecalledMetadata meta = recaller.recall("各品类金额是多少");

        assertThat(recalledIds(meta)).contains("sale_amount_product");
    }
}
