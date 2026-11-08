package com.wuyunbin.rag.retrieval;

import com.wuyunbin.rag.dto.RetrievalOptions;
import com.wuyunbin.rag.dto.RetrievedDocument;

import java.util.List;

/**
 * 文档级检索抽象：在切片检索之上做文档聚合与排序。
 */
public interface DocumentRetriever {

    /**
     * 文档搜索：先 Chunk 检索，再按 documentId 聚合。
     *
     * @param query   查询文本
     * @param options 检索参数
     * @return 命中文档列表（按 documentScore 降序），可能为空
     */
    List<RetrievedDocument> search(String query, RetrievalOptions options);
}
