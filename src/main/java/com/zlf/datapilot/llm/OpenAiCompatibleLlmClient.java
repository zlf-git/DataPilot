package com.zlf.datapilot.llm;

import com.zlf.datapilot.ir.QueryIr;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容协议的通用真实 LLM 实现（Ticket 08）。
 *
 * <p>DeepSeek 与 Qwen（百炼 DashScope 兼容模式）在协议层是同一套：
 * {@code POST {base-url}/v1/chat/completions} + {@code Authorization: Bearer <key>} +
 * OpenAI 的 {@code messages} 结构。所以不按厂商各写一个实现类
 * （原来只有 {@code DeepSeekLlmClient}，加 Qwen 就得复制一份），而是把
 * <b>base-url / model / key 参数化</b>，由 {@link LlmClientFactory} 从配置解析后注入。
 * 将来接 Kimi / 智谱 / 本地 vLLM，只要兼容 OpenAI 协议就只加一个枚举项。
 *
 * <p>结构化输出仍走 {@code .entity(QueryIr.class)}：LLM 只能返回 IR，不能返回自由文本 SQL
 * （这是「LLM 不碰 SQL」的结构性约束，换模型不能松）。
 *
 * <p>超时来自 {@code LlmHttpConfig} 提供的 {@link RestClient.Builder}（connect 3s / read 30s），
 * 不是 Spring AI 自动配置那份无超时的 builder —— 网络黑洞必须显式失败。
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final LlmTarget target;
    private final ChatClient chatClient;

    public OpenAiCompatibleLlmClient(LlmTarget target, RestClient.Builder restClientBuilder) {
        this.target = target;
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(target.baseUrl())
                .apiKey(target.apiKey())
                .restClientBuilder(restClientBuilder)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(target.model())
                .temperature(target.temperature())
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public LlmGeneration generate(String systemPrompt, String userPrompt) {
        // responseEntity 而不是 entity：要把 ChatResponse 的 usage 一起拿回来，
        // 否则 cost 恒为 0，「成本」这个指标等于没接（QA-08 P2-5）。
        ResponseEntity<ChatResponse, QueryIr> resp = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .responseEntity(QueryIr.class);

        LlmGeneration g = new LlmGeneration();
        g.setIr(resp.entity());
        Usage usage = resp.response() == null ? null : resp.response().getMetadata().getUsage();
        if (usage != null) {
            g.setPromptTokens(nvl(usage.getPromptTokens()));
            g.setCompletionTokens(nvl(usage.getCompletionTokens()));
            g.setTotalTokens(nvl(usage.getTotalTokens()));
        }
        return g;
    }

    /** 端点不回报 usage 时记 0（宁可成本显示 0，也不编一个假数字） */
    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    @Override
    public LlmDescriptor descriptor() {
        return LlmDescriptor.of(target);
    }

    /** 生效参数（只读，供排障与单测断言；不含明文 Key） */
    public LlmTarget target() {
        return target;
    }
}
