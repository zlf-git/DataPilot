package com.zlf.datapilot.llm;

/**
 * LLM 客户端接口：OpenAI 兼容的对话调用（DeepSeek / Qwen 均可）。
 */
public interface LlmClient {

    /**
     * 发起一次对话。
     *
     * @param systemPrompt 系统提示词（角色、约束）
     * @param userPrompt   用户提示词（问题 + 元数据上下文）
     * @return 模型返回的文本 + token 用量（AgentOps 成本统计依赖 token）
     */
    ChatResult chat(String systemPrompt, String userPrompt);
}
