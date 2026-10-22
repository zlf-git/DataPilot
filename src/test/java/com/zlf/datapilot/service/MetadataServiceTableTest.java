package com.zlf.datapilot.service;

import com.zlf.datapilot.event.MetadataChangedEvent;
import com.zlf.datapilot.exception.NotFoundException;
import com.zlf.datapilot.knowledge.MetadataRepository;
import com.zlf.datapilot.knowledge.model.SemanticModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 表注册/注销的校验逻辑（Ticket 19）。
 *
 * <p>评审把「加表只能手工 INSERT」列为「加表零改动不成立」的缺口，补接口的同时必须把
 * <b>删除前守卫</b>钉死：{@code metric_def.table_name}、{@code denominator_table}、
 * {@code dimension_def}、{@code metric_table}、字段白名单，五处引用任一漏查，
 * 删完之后的报错都会指向「指标关联的表不在白名单」，没人能反推出真实原因。
 */
class MetadataServiceTableTest {

    private MetadataRepository repo;
    private ApplicationEventPublisher publisher;
    private MetadataService service;

    @BeforeEach
    void setUp() {
        repo = mock(MetadataRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new MetadataService(repo, publisher);
        when(repo.listTables()).thenReturn(List.of());
        when(repo.listMetrics()).thenReturn(List.of());
        when(repo.listGateConfigs()).thenReturn(List.of());
    }

    private static SemanticModel table(String name) {
        SemanticModel m = new SemanticModel();
        m.setTableName(name);
        m.setTableComment("测试表");
        m.setBizDomain("交易");
        return m;
    }

    @Test
    @DisplayName("新增表：mode=create 且已注册 → 409，不覆盖已有登记")
    void putTableCreateOnlyRejectsExisting() {
        when(repo.findTable("ads_trade_overview_di")).thenReturn(table("ads_trade_overview_di"));

        assertThatThrownBy(() -> service.putTable(table("ads_trade_overview_di"), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("已注册");
        verify(repo, never()).saveTable(any());
    }

    @Test
    @DisplayName("新增表：upsert 覆盖注释与业务域，并发元数据变更事件")
    void putTableUpsertSavesAndPublishes() {
        when(repo.findTable("ads_new_table")).thenReturn(table("ads_new_table"));
        SemanticModel m = table("ads_new_table");
        m.setTableComment("  新表  ");

        SemanticModel saved = service.putTable(m, false);

        assertThat(saved.getTableComment()).isEqualTo("新表");
        verify(repo).saveTable(any());
        ArgumentCaptor<MetadataChangedEvent> ev = ArgumentCaptor.forClass(MetadataChangedEvent.class);
        verify(publisher).publishEvent(ev.capture());
        assertThat(ev.getValue().toString()).contains("ads_new_table");
    }

    @Test
    @DisplayName("新增表：表名非法直接拒绝（白名单按 table.field 比对，坏名字永远匹配不上）")
    void putTableRejectsIllegalName() {
        assertThatThrownBy(() -> service.putTable(table("ads-new-table"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table_name");
        assertThatThrownBy(() -> service.putTable(table(""), false))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).saveTable(any());
    }

    @Test
    @DisplayName("注销表：表不存在 → 404（东西没了和参数传错处置不同）")
    void deleteTableMissingIs404() {
        when(repo.findTable("ghost")).thenReturn(null);
        assertThatThrownBy(() -> service.deleteTable("ghost"))
                .isInstanceOf(NotFoundException.class);
        verify(repo, never()).deleteTable(anyString());
    }

    @Test
    @DisplayName("注销表：还有字段白名单 → 拒绝，不级联删字段")
    void deleteTableBlockedByFields() {
        when(repo.findTable("ads_x")).thenReturn(table("ads_x"));
        when(repo.countFieldsOfTable("ads_x")).thenReturn(7);

        assertThatThrownBy(() -> service.deleteTable("ads_x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("7 个字段");
        verify(repo, never()).deleteTable(anyString());
    }

    @Test
    @DisplayName("注销表：仍是指标主表 → 拒绝并点名是哪个指标")
    void deleteTableBlockedByMetricTableName() {
        when(repo.findTable("ads_x")).thenReturn(table("ads_x"));
        when(repo.metricIdsUsingTable("ads_x")).thenReturn(List.of("gmv_total"));

        assertThatThrownBy(() -> service.deleteTable("ads_x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gmv_total")
                .hasMessageContaining("metric_def.table_name");
        verify(repo, never()).deleteTable(anyString());
    }

    @Test
    @DisplayName("注销表：仍是复合指标的分母表 → 拒绝（跨表比值那条链）")
    void deleteTableBlockedByDenominator() {
        when(repo.findTable("ads_trade_overview_di")).thenReturn(table("ads_trade_overview_di"));
        when(repo.metricIdsUsingTableAsDenominator("ads_trade_overview_di"))
                .thenReturn(List.of("gmv_channel_ratio"));

        assertThatThrownBy(() -> service.deleteTable("ads_trade_overview_di"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gmv_channel_ratio")
                .hasMessageContaining("denominator_table");
        verify(repo, never()).deleteTable(anyString());
    }

    @Test
    @DisplayName("注销表：仍被维度字典引用 → 拒绝")
    void deleteTableBlockedByDimension() {
        when(repo.findTable("ads_x")).thenReturn(table("ads_x"));
        when(repo.dimensionNamesOfTable("ads_x")).thenReturn(List.of("渠道"));

        assertThatThrownBy(() -> service.deleteTable("ads_x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("维度字典")
                .hasMessageContaining("渠道");
        verify(repo, never()).deleteTable(anyString());
    }

    @Test
    @DisplayName("注销表：仍挂在 metric_table → 拒绝")
    void deleteTableBlockedByMetricTableRefs() {
        when(repo.findTable("ads_x")).thenReturn(table("ads_x"));
        when(repo.metricTableRefs("ads_x")).thenReturn(List.of("paid_order_cnt"));

        assertThatThrownBy(() -> service.deleteTable("ads_x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metric_table");
        verify(repo, never()).deleteTable(anyString());
    }

    @Test
    @DisplayName("注销表：无任何引用 → 真删并广播事件")
    void deleteTableSucceedsWhenUnreferenced() {
        when(repo.findTable("ads_old")).thenReturn(table("ads_old"));
        when(repo.countFieldsOfTable("ads_old")).thenReturn(0);

        service.deleteTable("ads_old");

        verify(repo).deleteTable("ads_old");
        verify(publisher).publishEvent(any(MetadataChangedEvent.class));
    }
}
