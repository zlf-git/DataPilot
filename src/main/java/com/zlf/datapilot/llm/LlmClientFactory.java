package com.zlf.datapilot.llm;

import com.zlf.datapilot.config.DatapilotProperties;
import org.springframework.web.client.RestClient;

import java.util.function.Function;

/**
 * LLM 客户端工厂（Ticket 08）：把 {@code datapilot.llm.*} 配置解析成端点参数，再产出真实 provider 客户端。
 *
 * <p><b>工厂只产真实 provider（deepseek / qwen），不产 stub。</b>
 * 桩由 Ticket 01 的 {@code StubLlmClient}（{@code @ConditionalOnProperty(provider=stub)}）单独装配，
 * 两条装配路径互斥且显式。让工厂能产 stub 的后果不是「多一个便利入口」，而是
 * 「配了 deepseek 但 Key 没读到 → 悄悄退回桩 → 拿到一张看着正常的错数」（审计 P0#2），
 * 所以 {@link #create} 遇到 {@code stub}（或空值）直接抛错，而不是回退。
 *
 * <p>配置解析与实例构建分开是有意的：
 * <ul>
 *   <li>{@link #resolve} 是纯函数（入参 = 配置 + 读环境变量的函数），可以脱离 Spring 上下文单测；</li>
 *   <li>{@link #create} 只负责把解析结果塞进 {@link OpenAiCompatibleLlmClient}，不重复判断。</li>
 * </ul>
 *
 * <p>三档来源优先级：显式配置 &gt; provider 默认值（base-url / model）&gt; 环境变量（api-key）。
 * 「切 provider 只改一行配置」就靠这条链：{@code provider: qwen} 之后 base-url 与模型名自动跟着换。
 */
public class LlmClientFactory {

    private final RestClient.Builder restClientBuilder;

    public LlmClientFactory(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    /**
     * 按配置创建真实 provider 客户端。
     *
     * @throws IllegalStateException provider 为 stub / 非法、Key 缺失、base-url 为空时
     */
    public LlmClient create(DatapilotProperties.Llm cfg) {
        return new OpenAiCompatibleLlmClient(resolve(cfg), restClientBuilder);
    }

    /** 解析生效端点参数（读系统环境变量拿 Key） */
    public LlmTarget resolve(DatapilotProperties.Llm cfg) {
        return resolve(cfg, System::getenv);
    }

    /**
     * 解析生效端点参数（Key 的读法可注入，便于离线单测）。
     *
     * @param cfg 配置，null 视为空配置（provider 缺省 = stub → 拒绝）
     * @param env 环境变量读取函数，参数是变量名
     */
    public LlmTarget resolve(DatapilotProperties.Llm cfg, Function<String, String> env) {
        String rawProvider = cfg == null ? null : cfg.getProvider();
        LlmProvider provider = LlmProvider.find(rawProvider)
                .orElseThrow(() -> new IllegalStateException(
                        "LlmClientFactory 只产真实 provider，不产 stub：datapilot.llm.provider=" + rawProvider
                                + "（可选值：" + LlmProvider.availableValues()
                                + "；stub 由 StubLlmClient 按 @ConditionalOnProperty 单独装配）"));

        String baseUrl = normalizeBaseUrl(firstNonBlank(
                cfg.getBaseUrl(), provider.defaultBaseUrl()));
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("datapilot.llm.base-url 为空，且 provider=" + provider.id()
                    + " 没有默认端点");
        }

        String model = firstNonBlank(cfg.getModel(), provider.defaultModel());
        if (model.isEmpty()) {
            throw new IllegalStateException("datapilot.llm.model 为空，且 provider=" + provider.id()
                    + " 没有默认模型");
        }

        String apiKey = firstNonBlank(cfg.getApiKey(), env == null ? null : env.apply(provider.apiKeyEnv()));
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("provider=" + provider.id() + " 缺少 API Key："
                    + "设置环境变量 " + provider.apiKeyEnv() + " 或配置 datapilot.llm.api-key");
        }

        return new LlmTarget(provider, baseUrl, model, apiKey, cfg.getTemperature());
    }

    /**
     * base-url 归一化：去首尾空白、去尾斜杠、剥掉结尾多余的 {@code /v1}。
     *
     * <p>Spring AI 的 {@code OpenAiApi} 自己拼 {@code /v1/chat/completions}，
     * 用户按习惯写成 {@code https://dashscope.aliyuncs.com/compatible-mode/v1} 会拼出
     * {@code /v1/v1/chat/completions} 的 404 —— 这种错在真实调用时才暴露，且报错只有一句 404，
     * 排查成本远高于这里剥一下。
     *
     * <p>只剥<b>结尾</b>的 {@code /v1}，且<b>只剥一层</b>：{@code .../compatible-mode/v1} → {@code .../compatible-mode}；
     * 写成 {@code .../v1/v1} 时只剥掉最后一层，剩下的 {@code /v1} 当作路径保留 ——
     * 私有网关确实存在「路径本身以 /v1 结尾且需要保留」的部署，while 一路剥到 host 是静默改写（QA-08 P2-7）。
     * 中间出现的 v1（如代理网关路径 {@code /gw/v1-beta/x}）不动。
     */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("/v1")) {
            s = s.substring(0, s.length() - 3);
            while (s.endsWith("/")) {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }
}
