package com.wuyunbin.rag.retrieval;

import com.wuyunbin.rag.dto.RetrievalOptions;
import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkEntity;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
import com.wuyunbin.rag.service.ChunkTokenizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * PostgreSQL FTS 关键词检索实现。
 *
 * <p>查询文本经 {@link ChunkTokenizer} 分词后传入 {@code plainto_tsquery('simple', ...)}，
 * 与 {@code to_tsvector('simple', tokens)} 匹配。documentIds 通过 SQL {@code doc_md5 IN (...)} 下推。</p>
 *
 * <p>注意：ts_rank 不是归一化相似度，不直接与向量 score 同量纲；
 * 后续 RRF 融合阶段会用排名（而非分数）做合并，避免量纲不一致问题。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostgresKeywordRetriever implements KeywordRetriever {

    private final DocumentChunkJpaRepository chunkRepository;
    private final ChunkTokenizer chunkTokenizer;

    @Override
    public List<RetrievedChunk> search(String query, RetrievalOptions options) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        String tokenized = chunkTokenizer.tokenizeForQuery(query);
        if (tokenized.isBlank()) {
            return Collections.emptyList();
        }
        boolean hasFilter = options.hasDocumentFilter();
        // nativeQuery 的 IN 子句不接受空集合；无过滤时传 dummy 值，由 docFilter=false 恒真短路
        Set<String> docIds = hasFilter ? options.documentIds() : Set.of("__no_filter__");
        List<DocumentChunkEntity> hits = chunkRepository.searchByKeywords(
                tokenized, hasFilter, docIds, options.topK());
        log.debug("关键词检索完成 query={} topK={} docFilter={} 命中{}条",
                query, options.topK(), hasFilter ? options.documentIds().size() : 0, hits.size());
        return hits.stream().map(this::toChunk).toList();
    }

    private RetrievedChunk toChunk(DocumentChunkEntity e) {
        return new RetrievedChunk(
                e.getChunkId(),
                e.getDocMd5(),
                e.getFileName(),
                e.getContent(),
                e.getSectionPath(),
                e.getPageStart(),
                e.getPageEnd(),
                e.getChunkIndex(),
                // ts_rank 非归一化，暂填 0.0；RRF 阶段用排名而非分数
                0.0
        );
    }
}
