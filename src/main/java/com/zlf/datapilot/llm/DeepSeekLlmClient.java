package com.zlf.datapilot.llm;

import com.zlf.datapilot.config.DatapilotProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek LLM 实现：通过 OpenAI 兼容的 /chat/completions 接口调用。
 * 用 Spring 自带的 RestClient（web starter 内置），不额外引 SDK。
 */
@Component
public class DeepSeekLlmClient implements LlmClient {

    private final DatapilotProperties props;
    private final RestClient restClient;

    public DeepSeekLlmClient(DatapilotProperties props) {
        this.props = props;
        this.restClient = RestClient.builder()
                .baseUrl(props.getLlm().getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getLlm().getApiKey())
                .build();
    }

    @Override
    public ChatResult chat(String systemPrompt, String userPrompt) {
        Map<String, Object> request = Map.of(
                "model", props.getLlm().getModel(),
                "temperature", 0.1,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        Map<?, ?> response = restClient.post()
                .uri("/chat/completions")
                .body(request)
                .retrieve()
                .body(Map.class);

        ChatResult result = new ChatResult();

        List<?> choices = (List<?>) response.get("choices");
        if (choices != null && !choices.isEmpty()) {
            Map<?, ?> first = (Map<?, ?>) choices.get(0);
            Map<?, ?> message = (Map<?, ?>) first.get("message");
            if (message != null && message.get("content") != null) {
                result.setContent(String.valueOf(message.get("content")));
            }
        }

        Map<?, ?> usage = (Map<?, ?>) response.get("usage");
        if (usage != null) {
            result.setPromptTokens(toInt(usage.get("prompt_tokens")));
            result.setCompletionTokens(toInt(usage.get("completion_tokens")));
            result.setTotalTokens(toInt(usage.get("total_tokens")));
        }
        return result;
    }

    private int toInt(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(o.toString());
    }
}
