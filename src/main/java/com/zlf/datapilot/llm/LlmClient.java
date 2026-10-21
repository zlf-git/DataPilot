package com.zlf.datapilot.llm;

/**
 * LLM 客户端薄封装接口（钉死一层抽象）。
 * 输入 system + user prompt，输出结构化 {@link LlmGeneration}（含 QueryIr + token 用量）。
 *
 * <p>两条互斥装配路径：
 * <ul>
 *   <li>{@link StubLlmClient}：{@code provider=stub}，读 {@code stub_ir}，离线可跑；</li>
 *   <li>{@link OpenAiCompatibleLlmClient}：{@code provider=deepseek|qwen}，由
 *       {@link LlmClientFactory} 产出（Ticket 08），厂商标差异全在配置里。</li>
 * </ul>
 * 本接口保持单一抽象，不为厂商各加一层。
 */
public interface LlmClient {

    /**
     * 让 LLM 把自然语言问题生成结构化查询意图（IR）。
     *
     * @param systemPrompt 系统提示词（Ontology 白名单 + 输出约束）
     * @param userPrompt   用户提示词（问题 + 召回元数据）
     * @return 结构化 IR + token 用量
     */
    LlmGeneration generate(String systemPrompt, String userPrompt);

    /**
     * 运行时描述（provider / base-url / model / 是否配了 Key），供 {@code GET /api/health/llm}
     * 与启动自检。默认实现给不出信息，真实实现与桩各自覆盖。
     */
    default LlmDescriptor descriptor() {
        return new LlmDescriptor(getClass().getSimpleName(), null, null, false, 0.0);
    }
}
