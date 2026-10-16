package com.zlf.datapilot.llm;

import com.zlf.datapilot.config.DatapilotProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Qwen Embedding 实现：通过 OpenAI 兼容的 /embeddings 接口调用 text-embedding-v3（1024 维）。
 */
@Component
public class QwenEmbeddingClient implements EmbeddingClient {

    private final DatapilotProperties props;
    private final RestClient restClient;

    public QwenEmbeddingClient(DatapilotProperties props) {
        this.props = props;
        this.restClient = RestClient.builder()
                .baseUrl(props.getEmbedding().getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getEmbedding().getApiKey())
                .build();
    }

    @Override
    public List<Float> embed(String text) {
        Map<String, Object> request = Map.of(
                "model", props.getEmbedding().getModel(),
                "input", text
        );

        Map<?, ?> response = restClient.post()
                .uri("/embeddings")
                .body(request)
                .retrieve()
                .body(Map.class);

        List<?> data = (List<?>) response.get("data");
        if (data == null || data.isEmpty()) {
            return List.of();
        }
        Map<?, ?> first = (Map<?, ?>) data.get(0);
        List<?> vector = (List<?>) first.get("embedding");

        List<Float> result = new ArrayList<>();
        if (vector != null) {
            for (Object o : vector) {
                if (o instanceof Number n) {
                    result.add(n.floatValue());
                }
            }
        }
        return result;
    }
}
