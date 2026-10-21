package com.zlf.datapilot.llm;

import com.zlf.datapilot.ir.QueryIr;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DeepSeek LLM 实现：provider=deepseek 时装配，用 Spring AI ChatClient 包 DeepSeek。
 * 连接信息走 spring.ai.openai.*（DeepSeek 与 OpenAI 协议兼容），
 * 通过 {@code .entity(QueryIr.class)} 结构化输出，强制 LLM 返回合法 IR 而非自由文本。
 */
@Component
@ConditionalOnProperty(name = "datapilot.llm.provider", havingValue = "deepseek")
public class DeepSeekLlmClient implements LlmClient {

    private final ChatClient chatClient;

    public DeepSeekLlmClient(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public LlmGeneration generate(String systemPrompt, String userPrompt) {
        QueryIr ir = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .entity(QueryIr.class);

        LlmGeneration g = new LlmGeneration();
        g.setIr(ir);
        // token 用量由 Ticket 09 从 ChatResponse 的 metadata 补全
        return g;
    }
}
