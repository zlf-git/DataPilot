package com.zlf.datapilot.vector;

import java.util.List;
import java.util.Map;

/**
 * 向量库抽象（"向量轴"可插拔：Milvus / PGVector 均可实现）。
 * 用于元数据语义召回：把问题向量化后在向量库中检索相关的表/字段/指标。
 */
public interface VectorStore {

    /**
     * 写入 / 更新一条向量。
     *
     * @param collection 集合名
     * @param id         主键 id（如 "table_orders" / "field_amount"）
     * @param vector     向量
     * @param meta       附加元数据（如 text / entity_type / ref_table）
     */
    void upsert(String collection, String id, List<Float> vector, Map<String, String> meta);

    /**
     * 向量相似度检索，返回最相似的 topK 个 id。
     *
     * @param collection  集合名
     * @param queryVector 查询向量
     * @param topK        返回条数
     * @return 相似 id 列表（按相似度降序）
     */
    List<String> search(String collection, List<Float> queryVector, int topK);
}
