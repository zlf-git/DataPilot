package com.zlf.datapilot.llm;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 真实 LLM provider 清单（Ticket 08）。
 *
 * <p>只有<b>真实</b> provider 进这个枚举：{@code deepseek} / {@code qwen} 都走 OpenAI 兼容协议
 * （{@code /v1/chat/completions} + {@code Authorization: Bearer <key>}），差别只在 base-url、模型名、
 * Key 的环境变量名。桩 {@code stub} <b>不是</b> provider，它由 Ticket 01 的
 * {@code @ConditionalOnProperty(provider=stub)} 单独装配，工厂不产、枚举里也没有它
 * （审计 P0#2：工厂一旦能产 stub，「切真实模型」就会被静默降级成桩而不报错）。
 *
 * <p>默认值只做「不配也能跑」的兜底，三处都可以在 {@code datapilot.llm.*} 里覆盖
 * （私有化部署、代理网关、换具体模型版本都不需要改代码）。
 *
 * <p><b>base-url 不带 {@code /v1}</b>：Spring AI 的 {@code OpenAiApi} 自己拼 {@code /v1/chat/completions}，
 * 写成 {@code .../v1} 会拼出 {@code /v1/v1/chat/completions} 的 404。工厂侧另有归一化兜底
 * （{@link LlmClientFactory#normalizeBaseUrl}），但默认值本身按规范形态给。
 */
public enum LlmProvider {

    /** DeepSeek 官方 API：base-url 不带 /v1，模型 deepseek-chat（deepseek-reasoner 可配置切换） */
    DEEPSEEK("deepseek", "https://api.deepseek.com", "deepseek-chat", "DEEPSEEK_API_KEY"),

    /**
     * 阿里云百炼 DashScope 的 OpenAI 兼容端点。
     * 注意 base-url 到 {@code /compatible-mode} 为止（其完整路径是
     * {@code /compatible-mode/v1/chat/completions}，/v1 由 Spring AI 补）。
     */
    QWEN("qwen", "https://dashscope.aliyuncs.com/compatible-mode", "qwen-plus", "DASHSCOPE_API_KEY");

    private final String id;
    private final String defaultBaseUrl;
    private final String defaultModel;
    private final String apiKeyEnv;

    LlmProvider(String id, String defaultBaseUrl, String defaultModel, String apiKeyEnv) {
        this.id = id;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.apiKeyEnv = apiKeyEnv;
    }

    /** 配置里写的名字（小写） */
    public String id() {
        return id;
    }

    /** 未配置 {@code datapilot.llm.base-url} 时的默认端点 */
    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    /** 未配置 {@code datapilot.llm.model} 时的默认模型 */
    public String defaultModel() {
        return defaultModel;
    }

    /** 未配置 {@code datapilot.llm.api-key} 时读的环境变量名 */
    public String apiKeyEnv() {
        return apiKeyEnv;
    }

    /** 全部可选值（异常文案用）：deepseek / qwen / stub */
    public static String availableValues() {
        String real = Arrays.stream(values()).map(LlmProvider::id).collect(Collectors.joining(" / "));
        return real + " / stub";
    }

    /**
     * 按配置值找真实 provider（大小写、首尾空白不敏感）。
     *
     * @return {@code stub} → 空（合法的「非真实 provider」，由桩实现接管）；
     *         {@code deepseek}/{@code qwen} → 对应枚举
     * @throws IllegalStateException 值不在可选范围内时<b>立刻抛出</b>，与 Ticket 07 的
     *         {@code engine.type} 同款：配置写错启动即失败，不留「没有 LlmClient bean →
     *         问到第一步才 NoSuchBeanDefinition」的隐式坑
     */
    public static Optional<LlmProvider> find(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || "stub".equals(v)) {
            return Optional.empty();
        }
        for (LlmProvider p : values()) {
            if (p.id.equals(v)) {
                return Optional.of(p);
            }
        }
        throw new IllegalStateException("datapilot.llm.provider=" + raw + " 不受支持，可选值：" + availableValues());
    }
}
