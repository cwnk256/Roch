package com.wuyunbin.rag.retrieval;

import com.wuyunbin.rag.dto.RetrievalOptions;
import com.wuyunbin.rag.dto.RetrievedChunk;

import java.util.List;

/**
 * 关键词检索抽象：基于 PostgreSQL FTS（Jieba 分词 + simple 配置）召回切片。
 *
 * <p>与 {@link ChunkRetriever}（向量）并列，供后续 RetrievalPipeline 做 Hybrid + RRF 融合。
 * documentIds 约束必须贯穿：通过 SQL 的 doc_md5 IN (...) 下推到数据库。</p>
 */
public interface KeywordRetriever {

    /**
     * 关键词检索。
     *
     * @param query   查询文本（Java 侧用 Jieba 分词后传入 plainto_tsquery）
     * @param options 检索参数（topK、documentIds 过滤）
     * @return 命中的切片列表（按 ts_rank 降序），可能为空
     */
    List<RetrievedChunk> search(String query, RetrievalOptions options);
}
