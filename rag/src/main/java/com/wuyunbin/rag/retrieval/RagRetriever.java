package com.wuyunbin.rag.retrieval;

import com.wuyunbin.rag.cleaning.CleaningProperties;
import com.wuyunbin.rag.cleaning.TextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 检索服务：从 Milvus 向量库中召回与查询相关的文档切片。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagRetriever {

    private final VectorStore vectorStore;
    private final CleaningProperties cleaningProperties;
    private final TextNormalizer textNormalizer;

    /**
     * 检索与查询相关的文档切片。
     * 入库时若启用了小写化，查询侧执行同样的归一（URL 保护），保证两侧口径一致。
     *
     * @param query     用户查询文本
     * @param topK      召回条数
     * @param threshold 相似度阈值
     * @return 命中的文档切片列表，可能为空
     */
    public List<Document> retrieve(String query, int topK, double threshold) {
        String normalizedQuery = cleaningProperties.isEnabled() && cleaningProperties.isLowercase()
                ? textNormalizer.normalizeProse(query)
                : query;
        SearchRequest request = SearchRequest.builder()
                .query(normalizedQuery)
                .topK(topK)
                .similarityThreshold(threshold)
                .build();
        List<Document> results = vectorStore.similaritySearch(request);
        log.debug("检索完成 query={} topK={} threshold={} 命中{}条", normalizedQuery, topK, threshold, results.size());
        return results;
    }
}
