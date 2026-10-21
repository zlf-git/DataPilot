package com.zlf.datapilot.service;

import com.zlf.datapilot.event.MetadataChangedEvent;
import com.zlf.datapilot.knowledge.MetadataRepository;
import com.zlf.datapilot.knowledge.model.FieldMetadata;
import com.zlf.datapilot.knowledge.model.SemanticModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

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
 * 字段白名单写接口的校验逻辑（Ticket 13）。
 *
 * <p>为什么单独立一个测试类：{@code field_metadata} 就是存在性/语法闸门的字段白名单，
 * 写错一条的后果不是「页面显示难看」，而是「闸门放行一个不存在的列，一路到 JDBC 才炸」。
 * 所以每条拒绝路径都要有一条用例钉死，而不是靠页面上手点。
 */
class MetadataServiceFieldTest {

    private MetadataRepository repo;
    private ApplicationEventPublisher publisher;
    private MetadataService service;

    @BeforeEach
    void setUp() {
        repo = mock(MetadataRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new MetadataService(repo, publisher);
        when(repo.listTables()).thenReturn(List.of(table("ads_channel_conversion_di")));
        when(repo.listMetrics()).thenReturn(List.of());
        when(repo.listGateConfigs()).thenReturn(List.of());
    }

    private static SemanticModel table(String name) {
        SemanticModel m = new SemanticModel();
        m.setTableName(name);
        return m;
    }

    private static FieldMetadata field(String table, String name, String role, String enums) {
        FieldMetadata f = new FieldMetadata();
        f.setTableName(table);
        f.setFieldName(name);
        f.setFieldType("varchar(32)");
        f.setFieldComment("测试字段");
        f.setFieldRole(role);
        f.setEnumValues(enums);
        return f;
    }

    @Test
    @DisplayName("表未在 semantic_model 注册 → 拒绝，且不落库")
    void rejectsFieldOnUnregisteredTable() {
        assertThatThrownBy(() -> service.putField(field("t_ghost", "c1", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未在 semantic_model 注册");
        verify(repo, never()).saveField(any());
    }

    @Test
    @DisplayName("字段名带空格/大写 → 拒绝（白名单是逐字比对的，写错了匹配不上）")
    void rejectsIllegalFieldName() {
        assertThatThrownBy(() -> service.putField(field("ads_channel_conversion_di", "Channel Name", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field_name 非法");
        verify(repo, never()).saveField(any());
    }

    @Test
    @DisplayName("表名为空 → 拒绝")
    void rejectsBlankTableName() {
        assertThatThrownBy(() -> service.putField(field("  ", "channel", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table_name 不能为空");
    }

    @Test
    @DisplayName("field_role 写中文/其它值 → 拒绝（不默默存进白名单）")
    void rejectsUnknownRole() {
        assertThatThrownBy(() -> service.putField(field("ads_channel_conversion_di", "channel", "维度", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field_role 非法");
        verify(repo, never()).saveField(any());
    }

    @Test
    @DisplayName("field_role 大小写/空格归一化，空串存 null")
    void normalizesRole() {
        FieldMetadata upper = field("ads_channel_conversion_di", "channel", " Dimension ", null);
        service.putField(upper);
        assertThat(upper.getFieldRole()).isEqualTo("dimension");

        FieldMetadata blank = field("ads_channel_conversion_di", "channel", "  ", null);
        service.putField(blank);
        assertThat(blank.getFieldRole()).isNull();
    }

    @Test
    @DisplayName("enum_values 不是 JSON 数组 → 拒绝（对象/坏 JSON 都拒）")
    void rejectsNonArrayEnumValues() {
        assertThatThrownBy(() -> service.putField(field("ads_channel_conversion_di", "channel", null, "{\"a\":1}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须是 JSON 数组");

        assertThatThrownBy(() -> service.putField(field("ads_channel_conversion_di", "channel", null, "[APP,")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是合法 JSON");
        verify(repo, never()).saveField(any());
    }

    @Test
    @DisplayName("enum_values 空串归一成 null（JSON 列写 '' 会被 MySQL 拒，前端留空就是这么传的）")
    void blankEnumValuesBecomeNull() {
        FieldMetadata f = field("ads_channel_conversion_di", "channel", null, "   ");
        service.putField(f);
        assertThat(f.getEnumValues()).isNull();
        verify(repo).saveField(f);
    }

    @Test
    @DisplayName("合法写入 → 落库 + 发布 field 变更事件（白名单缓存据此刷新）")
    void putFieldSavesAndPublishes() {
        FieldMetadata f = field("ads_channel_conversion_di", "channel_name", "dimension", "[\"APP\",\"小程序\"]");
        service.putField(f);

        verify(repo).saveField(f);
        ArgumentCaptor<MetadataChangedEvent> captor = ArgumentCaptor.forClass(MetadataChangedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().entityType()).isEqualTo("field");
        assertThat(captor.getValue().entityKey()).isEqualTo("ads_channel_conversion_di.channel_name");
    }

    @Test
    @DisplayName("删除不存在的字段 → 拒绝")
    void deleteRejectsMissingField() {
        when(repo.findField(anyString(), anyString())).thenReturn(null);
        assertThatThrownBy(() -> service.deleteField("ads_channel_conversion_di", "nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字段不存在");
        verify(repo, never()).deleteField(anyString(), anyString());
    }

    @Test
    @DisplayName("删除仍被维度字典引用的字段 → 拒绝，且给出引用条数")
    void deleteRejectsFieldReferencedByDimension() {
        when(repo.findField(anyString(), anyString())).thenReturn(field("ads_channel_conversion_di", "channel", null, null));
        when(repo.countDimensionRefs(anyString(), anyString())).thenReturn(2);

        assertThatThrownBy(() -> service.deleteField("ads_channel_conversion_di", "channel"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仍被维度字典引用（2 条）");
        verify(repo, never()).deleteField(anyString(), anyString());
    }

    @Test
    @DisplayName("删除仍出现在指标可用维度里的字段 → 拒绝，并点名是哪个指标")
    void deleteRejectsFieldReferencedByMetric() {
        when(repo.findField(anyString(), anyString())).thenReturn(field("ads_channel_conversion_di", "channel", null, null));
        when(repo.countDimensionRefs(anyString(), anyString())).thenReturn(0);
        when(repo.metricIdsUsingField(anyString())).thenReturn(List.of("pay_conversion_rate"));

        assertThatThrownBy(() -> service.deleteField("ads_channel_conversion_di", "channel"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pay_conversion_rate");
        verify(repo, never()).deleteField(anyString(), anyString());
    }

    @Test
    @DisplayName("无人引用的字段 → 真删 + 发布事件")
    void deleteRemovesUnreferencedField() {
        when(repo.findField(anyString(), anyString())).thenReturn(field("ads_channel_conversion_di", "channel", null, null));
        when(repo.countDimensionRefs(anyString(), anyString())).thenReturn(0);
        when(repo.metricIdsUsingField(anyString())).thenReturn(List.of());

        service.deleteField("ads_channel_conversion_di", "channel");

        verify(repo).deleteField("ads_channel_conversion_di", "channel");
        verify(publisher).publishEvent(any(MetadataChangedEvent.class));
    }

    @Test
    @DisplayName("表清单透传（元数据页左侧列表）")
    void listTablesDelegatesToRepository() {
        assertThat(service.listTables()).extracting(SemanticModel::getTableName)
                .containsExactly("ads_channel_conversion_di");
    }
}
