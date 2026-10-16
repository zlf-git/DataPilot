package com.zlf.datapilot.llm;

import java.util.List;

/**
 * 向量化接口：把文本转成向量，供 Milvus 语义召回使用。
 * 默认实现 QwenEmbeddingClient（text-embedding-v3，1024 维），可插拔替换。
 */
public interface EmbeddingClient {

    /**
     * 文本向量化。
     *
     * @param text 待向量化的文本（问题 / 表注释 / 字段注释 / 指标名 / 术语）
     * @return 浮点向量
     */
    List<Float> embed(String text);
}
