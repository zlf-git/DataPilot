package com.zlf.datapilot.llm;

/**
 * LLM 运行时的对外描述（Ticket 08），供 {@code GET /api/health/llm} 与启动日志自检。
 *
 * <p>为什么要有它：{@code datapilot.llm.provider} 是「切模型不改代码」的唯一开关，
 * 但配置写完到底连的是哪个端点、哪个模型、Key 有没有读到，光看 yml 是看不出来的
 * （环境变量没导、占位符没替换都会静默走默认值）。把这三个值暴露成只读描述，
 * 切完 provider 一眼能确认「真的换过来了」——与 Ticket 07 的 {@code /api/health/engine}
 * 同一套路，同时给工厂的单测一个不碰私有字段的断言面。
 *
 * @param provider         生效 provider：deepseek / qwen / stub
 * @param baseUrl          生效 base-url（stub 为 null）
 * @param model            生效模型名（stub 为 null）
 * @param apiKeyConfigured Key 是否读到（<b>不回显 Key 本身</b>）
 * @param temperature      采样温度（stub 为 0）
 */
public record LlmDescriptor(String provider, String baseUrl, String model,
                            boolean apiKeyConfigured, double temperature) {

    /** 桩的自我描述：没有端点、没有 Key */
    public static LlmDescriptor stub() {
        return new LlmDescriptor("stub", null, null, false, 0.0);
    }

    public static LlmDescriptor of(LlmTarget target) {
        return new LlmDescriptor(target.provider().id(), target.baseUrl(), target.model(),
                target.apiKeyConfigured(), target.temperature());
    }
}
