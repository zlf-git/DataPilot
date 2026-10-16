package com.zlf.datapilot.vector;

import com.zlf.datapilot.config.DatapilotProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Milvus 向量库实现（可插拔向量轴）。
 * 仅当 datapilot.milvus.enabled=true 时装配；未启用时 Recaller 降级关键词召回。
 *
 * Collection 约定（metadata_embeddings）：
 *   id        VARCHAR 主键（如 table_orders / field_amount）
 *   text      VARCHAR 原始文本
 *   embedding FLOAT_VECTOR dim=1024
 *   entity_type VARCHAR（table / field / metric / term）
 *   ref_table   VARCHAR（所属表）
 */
@Component
@ConditionalOnProperty(name = "datapilot.milvus.enabled", havingValue = "true")
public class MilvusVectorStore implements VectorStore {

    private final MilvusServiceClient client;

    public MilvusVectorStore(DatapilotProperties props) {
        this.client = new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(props.getMilvus().getHost())
                        .withPort(props.getMilvus().getPort())
                        .build());
    }

    @Override
    public void upsert(String collection, String id, List<Float> vector, Map<String, String> meta) {
        InsertParam insert = InsertParam.newBuilder()
                .withCollectionName(collection)
                .withFields(List.of(
                        new InsertParam.Field("id", List.of(id)),
                        new InsertParam.Field("embedding", List.of(vector)),
                        new InsertParam.Field("text", List.of(meta.getOrDefault("text", "")))))
                .build();
        R<MutationResult> resp = client.insert(insert);
        if (resp.getStatus() != 0) {
            throw new RuntimeException("Milvus upsert 失败: " + resp.getMessage());
        }
    }

    @Override
    public List<String> search(String collection, List<Float> queryVector, int topK) {
        SearchParam search = SearchParam.newBuilder()
                .withCollectionName(collection)
                .withMetricType(MetricType.COSINE)
                .withTopK(topK)
                .withVectorFieldName("embedding")
                .withParams("{\"nprobe\":10}")
                .withVectors(List.of(queryVector))
                .withOutFields(List.of("id"))
                .build();
        R<SearchResults> resp = client.search(search);
        if (resp.getStatus() != 0) {
            throw new RuntimeException("Milvus search 失败: " + resp.getMessage());
        }
        SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
        List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
        List<String> ids = new ArrayList<>();
        for (SearchResultsWrapper.IDScore s : scores) {
            ids.add(s.getStrID());
        }
        return ids;
    }
}
