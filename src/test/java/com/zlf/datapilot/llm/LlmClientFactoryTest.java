package com.zlf.datapilot.llm;

import com.zlf.datapilot.config.DatapilotProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ticket 08 多模型工厂单测：只断言<b>外部可观测行为</b>（解析出的端点参数、产出的客户端描述、异常文案），
 * 不 mock 内部构件、不真调模型（票面「mock，不真调」）。
 *
 * <p>Key 的读取用注入的 env 函数模拟（{@code Map::get}），不依赖跑测试的机器上真的导了
 * DEEPSEEK_API_KEY —— 靠真环境变量的测试在 CI 上一定飘。
 */
class LlmClientFactoryTest {

    private final LlmClientFactory factory = new LlmClientFactory(RestClient.builder());

    /** 只认识一个环境变量的假 env */
    private static java.util.function.Function<String, String> envOf(String name, String value) {
        return Map.of(name, value)::get;
    }

    private static DatapilotProperties.Llm cfg(String provider) {
        DatapilotProperties.Llm c = new DatapilotProperties.Llm();
        c.setProvider(provider);
        return c;
    }

    @Test
    @DisplayName("deepseek：不配 base-url/model 时取 provider 默认值（api.deepseek.com + deepseek-chat）")
    void deepseekDefaults() {
        DatapilotProperties.Llm c = cfg("deepseek");
        c.setApiKey("sk-test-deepseek-1234");

        LlmTarget t = factory.resolve(c, name -> null);

        assertThat(t.provider()).isEqualTo(LlmProvider.DEEPSEEK);
        assertThat(t.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(t.model()).isEqualTo("deepseek-chat");
        assertThat(t.temperature()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("qwen：不配 base-url/model 时取 DashScope 兼容模式 + qwen-plus")
    void qwenDefaults() {
        DatapilotProperties.Llm c = cfg("qwen");
        c.setApiKey("sk-test-qwen-1234");

        LlmTarget t = factory.resolve(c, name -> null);

        assertThat(t.provider()).isEqualTo(LlmProvider.QWEN);
        assertThat(t.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode");
        assertThat(t.model()).isEqualTo("qwen-plus");
    }

    @Test
    @DisplayName("切 provider 只改一个值：同一份配置换 provider，端点与模型整体跟着换")
    void switchingProviderOnlyChangesOneField() {
        DatapilotProperties.Llm c = cfg("deepseek");
        c.setApiKey("sk-test-12345678");
        LlmTarget deepseek = factory.resolve(c, name -> null);

        c.setProvider("qwen");
        LlmTarget qwen = factory.resolve(c, name -> null);

        assertThat(deepseek.baseUrl()).isNotEqualTo(qwen.baseUrl());
        assertThat(deepseek.model()).isNotEqualTo(qwen.model());
    }

    @Test
    @DisplayName("provider 大小写与首尾空白不敏感（DeepSeek / QWEN ）")
    void providerIsCaseInsensitive() {
        DatapilotProperties.Llm c = cfg("  QWEN ");
        c.setApiKey("sk-test-12345678");

        assertThat(factory.resolve(c, name -> null).provider()).isEqualTo(LlmProvider.QWEN);
    }

    @Test
    @DisplayName("显式配置覆盖 provider 默认值（私有化部署 / 代理网关 / 换模型版本）")
    void explicitConfigOverridesDefaults() {
        DatapilotProperties.Llm c = cfg("qwen");
        c.setBaseUrl("https://gw.internal.com/dashscope");
        c.setModel("qwen-max");
        c.setApiKey("sk-test-12345678");
        c.setTemperature(0.3);

        LlmTarget t = factory.resolve(c, name -> null);

        assertThat(t.baseUrl()).isEqualTo("https://gw.internal.com/dashscope");
        assertThat(t.model()).isEqualTo("qwen-max");
        assertThat(t.temperature()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("base-url 归一化：尾斜杠与结尾多余的 /v1 被剥掉，路径中间的 v1 不动")
    void baseUrlNormalized() {
        assertThat(LlmClientFactory.normalizeBaseUrl("https://api.deepseek.com/"))
                .isEqualTo("https://api.deepseek.com");
        assertThat(LlmClientFactory.normalizeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode");
        assertThat(LlmClientFactory.normalizeBaseUrl("https://host/v1/"))
                .isEqualTo("https://host");
        // 中间出现的 v1 是网关路径的一部分，不能剥
        assertThat(LlmClientFactory.normalizeBaseUrl("https://host/gw/v1-beta/x"))
                .isEqualTo("https://host/gw/v1-beta/x");
        assertThat(LlmClientFactory.normalizeBaseUrl(null)).isEmpty();
    }

    @Test
    @DisplayName("api-key 留空时按 provider 读环境变量（deepseek→DEEPSEEK_API_KEY / qwen→DASHSCOPE_API_KEY）")
    void apiKeyFallsBackToProviderEnv() {
        LlmTarget ds = factory.resolve(cfg("deepseek"), envOf("DEEPSEEK_API_KEY", "sk-from-env-deepseek"));
        LlmTarget qw = factory.resolve(cfg("qwen"), envOf("DASHSCOPE_API_KEY", "sk-from-env-qwen"));

        assertThat(ds.apiKey()).isEqualTo("sk-from-env-deepseek");
        assertThat(qw.apiKey()).isEqualTo("sk-from-env-qwen");
        assertThat(ds.apiKeyConfigured()).isTrue();
    }

    @Test
    @DisplayName("Key 缺失：显式失败并指出该设哪个环境变量，不静默退化")
    void missingApiKeyFailsLoudly() {
        assertThatThrownBy(() -> factory.resolve(cfg("deepseek"), name -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY")
                .hasMessageContaining("datapilot.llm.api-key");

        assertThatThrownBy(() -> factory.resolve(cfg("qwen"), name -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    @DisplayName("工厂不产 stub：provider=stub 或空值直接拒绝（stub 由 StubLlmClient 单独装配）")
    void factoryRefusesStub() {
        assertThatThrownBy(() -> factory.resolve(cfg("stub"), name -> "sk-x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只产真实 provider")
                .hasMessageContaining("stub");

        assertThatThrownBy(() -> factory.resolve(cfg(null), name -> "sk-x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只产真实 provider");
    }

    @Test
    @DisplayName("provider 写错：启动即失败，文案列出可选值（不留 NoSuchBeanDefinition 到问数时才炸）")
    void unknownProviderFailsWithChoices() {
        assertThatThrownBy(() -> factory.resolve(cfg("deepseek-v3"), name -> "sk-x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deepseek-v3")
                .hasMessageContaining("deepseek / qwen / stub");
    }

    @Test
    @DisplayName("产出的客户端描述与解析结果一致，且不回显 Key 明文")
    void createdClientDescribesItselfWithoutLeakingKey() {
        DatapilotProperties.Llm c = cfg("deepseek");
        c.setApiKey("sk-secret-value-9876");

        LlmClient client = factory.create(c);
        LlmDescriptor d = client.descriptor();

        assertThat(client).isInstanceOf(OpenAiCompatibleLlmClient.class);
        assertThat(d.provider()).isEqualTo("deepseek");
        assertThat(d.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(d.model()).isEqualTo("deepseek-chat");
        assertThat(d.apiKeyConfigured()).isTrue();
        // 描述里只有「有没有」，没有任何 Key 片段
        assertThat(d.toString()).doesNotContain("sk-secret-value-9876").doesNotContain("sk-secret");
    }

    @Test
    @DisplayName("LlmTarget 的脱敏 Key：短 Key 全脱，长 Key 只留首尾 4 位")
    void maskedApiKey() {
        LlmTarget shortKey = new LlmTarget(LlmProvider.DEEPSEEK, "https://api.deepseek.com",
                "deepseek-chat", "sk-123", 0.0);
        LlmTarget longKey = new LlmTarget(LlmProvider.DEEPSEEK, "https://api.deepseek.com",
                "deepseek-chat", "sk-abcdefghijklmn", 0.0);

        assertThat(shortKey.maskedApiKey()).isEqualTo("***");
        assertThat(longKey.maskedApiKey()).isEqualTo("sk-a***klmn");
    }

    @Test
    @DisplayName("桩的自我描述：provider=stub，无端点无模型无 Key")
    void stubDescriptor() {
        LlmDescriptor d = LlmDescriptor.stub();

        assertThat(d.provider()).isEqualTo("stub");
        assertThat(d.baseUrl()).isNull();
        assertThat(d.model()).isNull();
        assertThat(d.apiKeyConfigured()).isFalse();
    }
}
