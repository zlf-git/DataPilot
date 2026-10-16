package com.zlf.datapilot.knowledge;

import com.zlf.datapilot.knowledge.model.FieldMetadata;
import com.zlf.datapilot.knowledge.model.FewShotExample;
import com.zlf.datapilot.knowledge.model.MetricDef;
import com.zlf.datapilot.knowledge.model.RecalledMetadata;
import com.zlf.datapilot.knowledge.model.SemanticModel;
import com.zlf.datapilot.knowledge.model.TermDict;
import com.zlf.datapilot.llm.EmbeddingClient;
import com.zlf.datapilot.vector.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 召回逻辑：找「跟这个问题相关」的表、字段、指标、术语、few-shot。
 * 两路召回（关键词 + 向量），互为补充：
 *  1. 向量召回：问题 embedding → VectorStore.search(topK) → 相关 table/field。
 *  2. 关键词兜底：问题里的词直接匹配 term_dict / metric_def / 表注释。
 * 无 Milvus 环境时（VectorStore 未装配）自动降级为纯关键词召回，符合「可插拔」。
 */
@Component
public class Recaller {

    private static final String COLLECTION = "metadata_embeddings";

    private final MetadataRepository repo;
    private final EmbeddingClient embedding;
    private final VectorStore vectorStore;

    public Recaller(MetadataRepository repo,
                    EmbeddingClient embedding,
                    ObjectProvider<VectorStore> vectorStoreProvider) {
        this.repo = repo;
        this.embedding = embedding;
        // VectorStore 未装配（milvus.enabled=false）时返回 null → 降级关键词召回
        this.vectorStore = vectorStoreProvider.getIfAvailable();
    }

    public RecalledMetadata recall(String question) {
        RecalledMetadata result = new RecalledMetadata();
        String q = question == null ? "" : question;

        // 1. 关键词召回（兜底，总是执行）
        Set<String> hitTables = keywordRecall(q, result);

        // 2. 向量召回（有 Milvus 时补充增强，失败不阻断）
        if (vectorStore != null) {
            try {
                List<Float> vec = embedding.embed(q);
                if (vec != null && !vec.isEmpty()) {
                    List<String> ids = vectorStore.search(COLLECTION, vec, 10);
                    for (String id : ids) {
                        if (id != null && id.startsWith("table_")) {
                            hitTables.add(id.substring("table_".length()));
                        }
                    }
                }
            } catch (Exception e) {
                // 向量召回失败忽略，用关键词结果（冷启动降级）
            }
        }

        // 3. 兜底：召回 0 表时返回全量表（首版元数据量小）
        List<SemanticModel> allTables = repo.listTables();
        if (hitTables.isEmpty()) {
            hitTables.addAll(allTables.stream().map(SemanticModel::getTableName).toList());
        }

        // 4. 组装 DDL + 字段
        for (SemanticModel t : allTables) {
            if (hitTables.contains(t.getTableName())) {
                result.getDdlMap().put(t.getTableName(), buildDdl(t));
                result.getFields().addAll(repo.listFields(t.getTableName()));
            }
        }

        // 5. few-shot：取命中表的业务域
        allTables.stream()
                .filter(t -> hitTables.contains(t.getTableName()))
                .findFirst()
                .ifPresent(t -> result.setFewShots(repo.listFewShots(t.getBizDomain())));

        return result;
    }

    private Set<String> keywordRecall(String q, RecalledMetadata result) {
        Set<String> hitTables = new HashSet<>();
        String ql = q.toLowerCase();

        // 术语命中：问题里包含术语黑话
        List<TermDict> terms = repo.listTerms().stream()
                .filter(t -> t.getTerm() != null && q.contains(t.getTerm()))
                .collect(Collectors.toList());
        result.setTerms(terms);

        // 指标命中：问题里包含指标名 / 英文名
        List<MetricDef> metrics = repo.listMetrics(null).stream()
                .filter(m -> (m.getMetricName() != null && q.contains(m.getMetricName()))
                        || (m.getEnName() != null && ql.contains(m.getEnName().toLowerCase())))
                .collect(Collectors.toList());
        result.setMetrics(metrics);

        // 表命中：表名或表注释出现在问题里
        for (SemanticModel t : repo.listTables()) {
            if ((t.getTableName() != null && ql.contains(t.getTableName().toLowerCase()))
                    || (t.getTableComment() != null && q.contains(t.getTableComment()))) {
                hitTables.add(t.getTableName());
            }
        }
        return hitTables;
    }

    private String buildDdl(SemanticModel t) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(t.getTableName()).append(" (\n");
        for (FieldMetadata f : repo.listFields(t.getTableName())) {
            sb.append("  ").append(f.getFieldName())
                    .append(" ").append(f.getFieldType() == null ? "VARCHAR" : f.getFieldType());
            if (f.getFieldComment() != null) {
                sb.append(" -- ").append(f.getFieldComment());
            }
            if (f.getEnumValues() != null) {
                sb.append(" 枚举:").append(f.getEnumValues());
            }
            sb.append("\n");
        }
        sb.append(")");
        if (t.getTableComment() != null) {
            sb.append(" COMMENT='").append(t.getTableComment()).append("'");
        }
        return sb.toString();
    }
}
