package com.zlf.datapilot.llm;

/**
 * 一次 LLM 调用的最终生效参数（provider 默认值 + 配置覆盖的解析结果）。
 *
 * <p>单独抽出来的意义：把「配置怎么解析」和「怎么建 ChatClient」分开，
 * 解析是纯函数、可以脱离 Spring 上下文单测（Ticket 08 测试要求：断言两个 provider
 * 生成正确 base-url/模型名，mock，不真调）。
 *
 * @param provider    真实 provider（deepseek / qwen），不会是 stub
 * @param baseUrl     生效 base-url（已归一化：去尾斜杠、去多余的 /v1）
 * @param model       生效模型名
 * @param apiKey      生效 Key（可能是空串，构造时由工厂校验）
 * @param temperature 采样温度
 */
public record LlmTarget(LlmProvider provider, String baseUrl, String model, String apiKey, double temperature) {

    /** 日志/自检用的脱敏 Key：只留前 4 位与后 4 位，长度不足 12 直接全脱 */
    public String maskedApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        if (apiKey.length() < 12) {
            return "***";
        }
        return apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
    }

    /** 是否配了 Key（不含「脱敏后的值」这类判断，只看有无） */
    public boolean apiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
