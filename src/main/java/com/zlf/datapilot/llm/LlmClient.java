package com.zlf.datapilot.llm;

/**
 * LLM 客户端薄封装接口（钉死一层抽象）。
 * 输入 system + user prompt，输出结构化 {@link LlmGeneration}（含 QueryIr + token 用量）。
 * 两个实现：StubLlmClient（provider=stub，读 stub_ir）与 DeepSeekLlmClient（provider=deepseek，Spring AI）。
 * 其余真实 provider（Qwen 等）由 Ticket 08 的工厂产出，本接口保持单一抽象不叠层。
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
}
