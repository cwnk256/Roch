package com.wuyunbin.rag.retrieval;

import com.wuyunbin.rag.dto.RetrievalOptions;
import com.wuyunbin.rag.dto.RetrievedChunk;

import java.util.List;

/**
 * 切片检索抽象：只负责"根据查询找到相关切片"。
 *
 * <p>不承担 LLM / Prompt / ChatMemory / 文档聚合 等职责。</p>
 */
public interface ChunkRetriever {

    /**
     * 在向量库中检索与 query 相关的切片。
     *
     * @param query   查询文本
     * @param options 检索参数（topK、threshold、documentIds 过滤）
     * @return 命中的切片列表（按相似度降序），可能为空
     */
    List<RetrievedChunk> search(String query, RetrievalOptions options);
}
