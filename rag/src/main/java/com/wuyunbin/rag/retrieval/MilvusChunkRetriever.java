package com.wuyunbin.rag.retrieval;

import com.wuyunbin.rag.cleaning.CleaningProperties;
import com.wuyunbin.rag.cleaning.TextNormalizer;
import com.wuyunbin.rag.dto.RetrievalOptions;
import com.wuyunbin.rag.dto.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 基于 Spring AI Milvus VectorStore 的 ChunkRetriever 实现。
 *
 * <p>复用 {@link RagRetriever} 同款查询归一化逻辑（小写化），
 * 并通过 SearchRequest.filterExpression 实现 documentIds 过滤。</p>
 *
 * <p>documentIds 过滤走 Milvus 标量字段 metadata["doc_md5"] IN [...]，
 * 不在 Java 内存中后置过滤。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusChunkRetriever implements ChunkRetriever {

    private final VectorStore vectorStore;
    private final CleaningProperties cleaningProperties;
    private final TextNormalizer textNormalizer;

    @Override
    public List<RetrievedChunk> search(String query, RetrievalOptions options) {
        String normalizedQuery = cleaningProperties.isEnabled() && cleaningProperties.isLowercase()
                ? textNormalizer.normalizeProse(query)
                : query;

        SearchRequest.Builder requestBuilder = SearchRequest.builder()
                .query(normalizedQuery)
                .topK(options.topK())
                .similarityThreshold(options.threshold());

        // documentIds 过滤：通过 metadata["doc_md5"] IN [...] 走 Milvus 标量过滤
        if (options.hasDocumentFilter()) {
            Set<String> ids = options.documentIds();
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            // 转 Spring AI Filter：metadata.doc_md5 IN [id1, id2, ...]
            requestBuilder.filterExpression(
                    b.in("doc_md5", ids.toArray()).build());
        }

        SearchRequest request = requestBuilder.build();
        List<Document> results = vectorStore.similaritySearch(request);
        log.debug("文档检索完成 query={} topK={} threshold={} docFilter={} 命中{}条",
                normalizedQuery, options.topK(), options.threshold(),
                options.hasDocumentFilter() ? options.documentIds().size() : 0, results.size());
        return results.stream().map(RetrievedChunk::from).toList();
    }
}
