package com.zlf.datapilot.config;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentEngine;
import com.zlf.datapilot.controller.AskController;
import com.zlf.datapilot.controller.MetadataController;
import com.zlf.datapilot.knowledge.model.FieldMetadata;
import com.zlf.datapilot.knowledge.model.GateConfig;
import com.zlf.datapilot.knowledge.model.MetricDef;
import com.zlf.datapilot.knowledge.model.SemanticModel;
import com.zlf.datapilot.service.MetadataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权的外部行为测试（Ticket 14）。
 *
 * <p><b>只断言 HTTP 层看得见的东西</b>：状态码、响应体是不是 JSON、错误文案里有没有说清原因。
 * 不测 {@code SecurityFilterChain} 的 bean 结构、不测某个注解挂在哪 —— 那些改了实现就该跟着改的断言，
 * 只能在重构时制造摩擦，不能证明「未授权的人进不来」。
 *
 * <p>三种身份 × 三个写接口 × 一个有身份但角色不够的组合都在这里；
 * 最后三条额外锁住「票面最怕的那条」——加了 Spring Security 之后 {@code /api/ask} 不能被一起拦掉。
 */
@WebMvcTest(controllers = {MetadataController.class, AskController.class})
@Import(SecurityConfig.class)
class SecurityAccessTest {

    private static final String METRIC_BODY = """
            {"metricId":"zz_auth_metric","metricName":"鉴权用例","metricSql":"SUM(total_gmv)",
             "metricType":"atomic","tableName":"ads_trade_overview_di"}""";

    private static final String GATE_BODY = """
            {"gateName":"cost","paramKey":"max_join","paramValue":"8"}""";

    private static final String FIELD_BODY = """
            {"tableName":"ads_trade_overview_di","fieldName":"zz_auth_field","fieldType":"varchar(16)"}""";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MetadataService service;

    @MockitoBean
    private DatapilotProperties props;

    @MockitoBean
    private AgentEngine engine;

    /** 写开关默认打开（Ticket 14 起它是「运维急停」，不是安全兜底） */
    private void writeOpen(boolean open) {
        DatapilotProperties.Metadata metadata = new DatapilotProperties.Metadata();
        metadata.setWriteEnabled(open);
        when(props.getMetadata()).thenReturn(metadata);
    }

    // ---------------- ① 没有凭证：401 ----------------

    @Test
    @DisplayName("匿名 PUT /api/metrics → 401（且响应体是 JSON，说清要什么凭证）")
    void anonymousCannotWriteMetric() throws Exception {
        writeOpen(true);
        mvc.perform(put("/api/metrics").contentType(MediaType.APPLICATION_JSON).content(METRIC_BODY))
                .andExpect(status().isUnauthorized())
                // 空响应体是最没用的 401：前端只能显示「操作失败」。这里必须能读到原因
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("匿名 PUT /api/gate-config → 401")
    void anonymousCannotWriteGateConfig() throws Exception {
        writeOpen(true);
        mvc.perform(put("/api/gate-config").contentType(MediaType.APPLICATION_JSON).content(GATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("匿名 PUT /api/metadata/fields → 401（Ticket 13 新开的写口同样要身份）")
    void anonymousCannotWriteField() throws Exception {
        writeOpen(true);
        mvc.perform(put("/api/metadata/fields").contentType(MediaType.APPLICATION_JSON).content(FIELD_BODY))
                .andExpect(status().isUnauthorized());
    }

    // ---------------- ② 业务角色：有身份但不够，403（与 401 分得开） ----------------

    @Test
    @DisplayName("业务角色 PUT /api/metrics → 403（不是 401：补齐权限才有用，补凭证没用）")
    void businessCannotWriteMetric() throws Exception {
        writeOpen(true);
        mvc.perform(put("/api/metrics").contentType(MediaType.APPLICATION_JSON).content(METRIC_BODY)
                        .with(user("business").roles("BUSINESS")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("业务角色 PUT /api/gate-config → 403")
    void businessCannotWriteGateConfig() throws Exception {
        writeOpen(true);
        mvc.perform(put("/api/gate-config").contentType(MediaType.APPLICATION_JSON).content(GATE_BODY)
                        .with(user("business").roles("BUSINESS")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("业务角色 PUT /api/metadata/fields → 403")
    void businessCannotWriteField() throws Exception {
        writeOpen(true);
        mvc.perform(put("/api/metadata/fields").contentType(MediaType.APPLICATION_JSON).content(FIELD_BODY)
                        .with(user("business").roles("BUSINESS")))
                .andExpect(status().isForbidden());
    }

    // ---------------- ③ 管理员：三个写口都放行 ----------------

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("管理员 PUT /api/metrics → 200")
    void adminCanWriteMetric() throws Exception {
        writeOpen(true);
        when(service.putMetric(any(MetricDef.class))).thenReturn(new MetricDef());
        mvc.perform(put("/api/metrics").contentType(MediaType.APPLICATION_JSON).content(METRIC_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("管理员 PUT /api/gate-config → 200")
    void adminCanWriteGateConfig() throws Exception {
        writeOpen(true);
        when(service.putGateConfig(any(GateConfig.class))).thenReturn(new GateConfig());
        mvc.perform(put("/api/gate-config").contentType(MediaType.APPLICATION_JSON).content(GATE_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("管理员 PUT /api/metadata/fields → 200")
    void adminCanWriteField() throws Exception {
        writeOpen(true);
        when(service.putField(any(FieldMetadata.class))).thenReturn(new FieldMetadata());
        mvc.perform(put("/api/metadata/fields").contentType(MediaType.APPLICATION_JSON).content(FIELD_BODY))
                .andExpect(status().isOk());
    }

    // ---------------- ④ DBA：白名单可以，业务口径不行 ----------------

    @Test
    @WithMockUser(username = "dba", roles = "DBA")
    @DisplayName("DBA PUT /api/metadata/fields → 200（白名单归 DBA）")
    void dbaCanWriteField() throws Exception {
        writeOpen(true);
        when(service.putField(any(FieldMetadata.class))).thenReturn(new FieldMetadata());
        mvc.perform(put("/api/metadata/fields").contentType(MediaType.APPLICATION_JSON).content(FIELD_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "dba", roles = "DBA")
    @DisplayName("DBA PUT /api/metrics → 403（DBA 是 admin 的子集，不是别名）")
    void dbaCannotWriteMetric() throws Exception {
        writeOpen(true);
        mvc.perform(put("/api/metrics").contentType(MediaType.APPLICATION_JSON).content(METRIC_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "dba", roles = "DBA")
    @DisplayName("DBA PUT /api/gate-config → 403（不能自己给自己放宽闸门）")
    void dbaCannotWriteGateConfig() throws Exception {
        writeOpen(true);
        mvc.perform(put("/api/gate-config").contentType(MediaType.APPLICATION_JSON).content(GATE_BODY))
                .andExpect(status().isForbidden());
    }

    // ---------------- ⑤ 票面最怕的一条：加了 Security 别把问数拦掉 ----------------

    @Test
    @DisplayName("匿名 POST /api/ask → 200（票面验收：问数必须仍然匿名可用）")
    void anonymousCanAsk() throws Exception {
        when(engine.run(any(String.class), nullable(String.class))).thenReturn(new AgentContext());
        mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"10月19日小程序渠道的GMV是多少\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("匿名 GET /api/metrics、/api/gate-config、/api/metadata/fields 全 200（只保护写）")
    void anonymousCanReadEverything() throws Exception {
        when(service.listMetrics()).thenReturn(List.of());
        when(service.listGateConfigs()).thenReturn(List.of());
        when(service.listFields()).thenReturn(List.of());
        when(service.listTables()).thenReturn(List.of(new SemanticModel()));
        mvc.perform(get("/api/metrics")).andExpect(status().isOk());
        mvc.perform(get("/api/gate-config")).andExpect(status().isOk());
        mvc.perform(get("/api/metadata/fields")).andExpect(status().isOk());
        mvc.perform(get("/api/metadata/tables")).andExpect(status().isOk());
    }

    // ---------------- ⑥ 有身份 ≠ 允许写：急停开关仍然有效 ----------------

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("管理员 + 急停关闭 → 403，且文案说明是急停而不是权限")
    void adminBlockedWhenWriteStopped() throws Exception {
        writeOpen(false);
        mvc.perform(put("/api/metrics").contentType(MediaType.APPLICATION_JSON).content(METRIC_BODY))
                .andExpect(status().isForbidden());
        // 服务层一次都不该被调到：急停要在进入写逻辑之前生效
        Mockito.verify(service, Mockito.never()).putMetric(any(MetricDef.class));
    }
}
