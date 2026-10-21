package com.zlf.datapilot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlf.datapilot.config.DatapilotProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Ticket 08 装配矩阵测试：provider 与 LlmClient 实现的对应关系（只测容器外部可见的 bean 与描述，不真调模型）。
 *
 * <p>为什么要单独测装配：工厂单测只能证明「参数解析对了」，证明不了
 * 「{@code provider=stub} 时不会顺手装配一个真实客户端」、「{@code provider=qwen} 时容器里真的是 Qwen 端点」。
 * 这两条正是审计 P0#2 的落点。
 */
class LlmClientConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LlmClientConfig.class, PropsConfig.class)
            .withBean(RestClient.Builder.class, RestClient::builder,
                    bd -> bd.setPrimary(true));

    @Test
    @DisplayName("provider=stub：容器里不装配真实客户端（交给 Ticket 01 的 StubLlmClient）")
    void stubDoesNotWireRealClient() {
        runner.withPropertyValues("datapilot.llm.provider=stub")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(OpenAiCompatibleLlmClient.class);
                    assertThat(ctx).doesNotHaveBean(LlmClient.class);
                });
    }

    /** 含桩的上下文：StubLlmClient 读 stub_ir 需要 ObjectMapper，最小上下文里没有自动配置 */
    private ApplicationContextRunner stubRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(LlmClientConfig.class, StubLlmClient.class, PropsConfig.class)
                .withBean(RestClient.Builder.class, RestClient::builder, bd -> bd.setPrimary(true))
                .withBean(ObjectMapper.class, ObjectMapper::new, bd -> bd.setPrimary(true));
    }

    @Test
    @DisplayName("含桩的真实上下文：provider=stub 时容器里有且只有一个 LlmClient，且是桩（QA-08 P2-6）")
    void stubIsTheOnlyClientInRealContext() {
        stubRunner()
                .withPropertyValues("datapilot.llm.provider=stub")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(LlmClient.class);
                    assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(StubLlmClient.class);
                    assertThat(ctx).doesNotHaveBean(OpenAiCompatibleLlmClient.class);
                });
    }

    @Test
    @DisplayName("provider 留空（属性存在但空串）也要落到桩，不能一个 LlmClient 都没有（QA-08 P2-1）")
    void blankProviderFallsBackToStub() {
        stubRunner()
                .withPropertyValues("datapilot.llm.provider=")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(LlmClient.class);
                    assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(StubLlmClient.class);
                });
    }

    @Test
    @DisplayName("provider 整行缺失也要落到桩（默认值不是靠 yml 兜的）")
    void missingProviderFallsBackToStub() {
        stubRunner()
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(LlmClient.class);
                    assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(StubLlmClient.class);
                });
    }

    @Test
    @DisplayName("provider=deepseek：装配真实客户端，生效端点是 api.deepseek.com + deepseek-chat")
    void deepseekWiresRealClient() {
        runner.withPropertyValues(
                        "datapilot.llm.provider=deepseek",
                        "datapilot.llm.api-key=sk-test-deepseek")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(LlmClient.class);
                    LlmDescriptor d = ctx.getBean(LlmClient.class).descriptor();
                    assertThat(d.provider()).isEqualTo("deepseek");
                    assertThat(d.baseUrl()).isEqualTo("https://api.deepseek.com");
                    assertThat(d.model()).isEqualTo("deepseek-chat");
                    assertThat(d.apiKeyConfigured()).isTrue();
                });
    }

    @Test
    @DisplayName("provider=qwen：同一份代码装配出 DashScope 兼容模式端点 + qwen-plus")
    void qwenWiresRealClient() {
        runner.withPropertyValues(
                        "datapilot.llm.provider=qwen",
                        "datapilot.llm.api-key=sk-test-qwen",
                        "datapilot.llm.model=qwen-max")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(LlmClient.class);
                    LlmDescriptor d = ctx.getBean(LlmClient.class).descriptor();
                    assertThat(d.provider()).isEqualTo("qwen");
                    assertThat(d.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode");
                    assertThat(d.model()).isEqualTo("qwen-max");
                });
    }

    @Test
    @DisplayName("provider 写错：上下文启动失败（不是「启动了但没客户端」）")
    void unknownProviderFailsStartup() {
        runner.withPropertyValues(
                        "datapilot.llm.provider=qwem",
                        "datapilot.llm.api-key=sk-test")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasMessageContaining("qwem");
                });
    }

    @Test
    @DisplayName("真实 provider 缺 Key：上下文启动失败，不留「跑起来等第一个问题才炸」")
    void missingKeyFailsStartup() {
        runner.withPropertyValues("datapilot.llm.provider=deepseek")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasMessageContaining("DEEPSEEK_API_KEY");
                });
    }

    @Test
    @DisplayName("装配完成后的启动自检不因 provider 变化而崩（ApplicationReadyEvent 能正常触发）")
    void startupSelfCheckRunsWithoutCrash() {
        // 回归点：自检方法曾写成 reportLlm(LlmClient)，@EventListener 会把第一个参数当事件体传，
        // ApplicationReadyEvent 触发时抛 argument type mismatch —— 启动成功之后再崩，最难查。
        runner.withPropertyValues("datapilot.llm.provider=stub")
                .run(ctx -> {
                    ApplicationReadyEvent event = new ApplicationReadyEvent(
                            new SpringApplication(), new String[0],
                            (ConfigurableApplicationContext) ctx.getSourceApplicationContext(), Duration.ZERO);
                    assertThatCode(() -> ctx.publishEvent(event)).doesNotThrowAnyException();
                });

        runner.withPropertyValues("datapilot.llm.provider=qwen", "datapilot.llm.api-key=sk-test")
                .run(ctx -> {
                    ApplicationReadyEvent event = new ApplicationReadyEvent(
                            new SpringApplication(), new String[0],
                            (ConfigurableApplicationContext) ctx.getSourceApplicationContext(), Duration.ZERO);
                    assertThatCode(() -> ctx.publishEvent(event)).doesNotThrowAnyException();
                });
    }

    /** 只注册配置属性的最小上下文（属性绑定由 @EnableConfigurationProperties 完成） */
    @Configuration
    @EnableConfigurationProperties(DatapilotProperties.class)
    static class PropsConfig {
    }
}
