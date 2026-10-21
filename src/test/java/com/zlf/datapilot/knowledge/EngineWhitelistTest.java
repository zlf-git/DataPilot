package com.zlf.datapilot.knowledge;

import com.zlf.datapilot.sql.SqlExecutor;
import com.zlf.datapilot.sql.SqlCommand;
import com.zlf.datapilot.sql.TableMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 07 任务 4：引擎可读表白名单钩子的行为。
 *
 * <p>规则只有一条，但两个方向都得钉住：
 * <ul>
 *   <li>引擎<b>没声明</b>（空集合）→ 不做额外限制，完全听元数据（保证 Ticket 02 现有行为不变）；</li>
 *   <li>引擎<b>声明了</b> → 取交集（fail-closed：声明了但元数据没有 = 不可读，不会因为引擎放话就越权）。</li>
 * </ul>
 * 并验证自检报告能指出双向失配：切 MySQL 后元数据还指着 4 张 ADS 表时，{@code metadataNotDeclared} 必须报出来。
 */
class EngineWhitelistTest {

    private static SqlExecutor engine(String name, Set<String> declared) {
        SqlExecutor ex = mock(SqlExecutor.class);
        when(ex.engineName()).thenReturn(name);
        when(ex.readableTables()).thenReturn(declared);
        when(ex.query(org.mockito.ArgumentMatchers.any(SqlCommand.class))).thenReturn(List.of());
        when(ex.query(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        when(ex.listTables()).thenReturn(List.<TableMeta>of());
        return ex;
    }

    /** report() 里是 List<Object>，断言字符串序列时统一收口，避免泛型捕获噪音。 */
    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        return (List<String>) o;
    }

    private static OntologyCache cache(String... tables) {
        OntologyCache cache = mock(OntologyCache.class);
        when(cache.tables()).thenReturn(Set.of(tables));
        return cache;
    }

    @Test
    void emptyDeclarationFallsBackToMetadataWhitelist() {
        EngineWhitelist wl = new EngineWhitelist(engine("mysql", Set.of()), cache("orders", "ads_x"));
        assertThat(wl.effective()).containsExactlyInAnyOrder("orders", "ads_x");
        assertThat(wl.isReadable("orders")).isTrue();
        assertThat(wl.isReadable("agent_run")).isFalse();
        assertThat(wl.isReadable(null)).isFalse();
    }

    @Test
    void declaredIntersectsWithMetadataFailClosed() {
        EngineWhitelist wl = new EngineWhitelist(
                engine("starrocks", Set.of("ads_trade_overview_di", "ghost_table")),
                cache("ads_trade_overview_di", "ads_region_gmv_di"));
        // 声明了 ghost_table 但元数据没有 → 不可读（不是「引擎说有就有」）
        assertThat(wl.effective()).containsExactly("ads_trade_overview_di");
        assertThat(wl.isReadable("ghost_table")).isFalse();
    }

    @Test
    void reportShowsBothDirectionsOfMismatch() {
        EngineWhitelist wl = new EngineWhitelist(
                engine("mysql", Set.of("orders")),
                cache("ads_trade_overview_di", "ads_channel_conversion_di"));
        Map<String, Object> r = wl.report();
        assertThat(r.get("engine")).isEqualTo("mysql");
        assertThat(strings(r.get("declaredTables"))).containsExactly("orders");
        assertThat(strings(r.get("effectiveTables"))).isEmpty();
        assertThat(strings(r.get("declaredNotInMetadata"))).containsExactly("orders");
        assertThat(strings(r.get("metadataNotDeclared")))
                .containsExactly("ads_channel_conversion_di", "ads_trade_overview_di");
        assertThat(r.get("consistent")).isEqualTo(false);
    }

    @Test
    void reportIsConsistentWhenBothSidesAligned() {
        EngineWhitelist wl = new EngineWhitelist(
                engine("starrocks", Set.of("ads_trade_overview_di")),
                cache("ads_trade_overview_di"));
        Map<String, Object> r = wl.report();
        assertThat(r.get("consistent")).isEqualTo(true);
        assertThat(strings(r.get("effectiveTables"))).containsExactly("ads_trade_overview_di");
        assertThat(strings(r.get("declaredNotInMetadata"))).isEmpty();
    }

    /**
     * QA-07 P2-1 的回归点：<b>只有 metadataNotDeclared 非空</b>也要判失配。
     *
     * <p>这就是「引擎切到 MySQL、元数据还指着 4 张 StarRocks 表」的真实场景：
     * 引擎声明的表全都在元数据里（declaredNotInMetadata 为空），但元数据里有一堆当前引擎没有的表。
     * 修复前 consistent 只看第一个方向，于是这里报 true，运维按它告警会漏掉整个切换残留。
     */
    @Test
    void reportIsInconsistentWhenMetadataHasTablesEngineCannotSee() {
        EngineWhitelist wl = new EngineWhitelist(
                engine("mysql", Set.of("orders")),
                cache("orders", "ads_trade_overview_di", "ads_channel_conversion_di"));
        Map<String, Object> r = wl.report();

        assertThat(strings(r.get("declaredNotInMetadata"))).isEmpty();
        assertThat(strings(r.get("metadataNotDeclared")))
                .containsExactly("ads_channel_conversion_di", "ads_trade_overview_di");
        assertThat(r.get("consistent")).isEqualTo(false);
    }
}
