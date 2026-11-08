package com.wuyunbin.rag.retrieval;

import com.wuyunbin.rag.dto.RetrievalOptions;
import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.dto.RetrievedDocument;
import com.wuyunbin.rag.service.DocumentAggregationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认 DocumentRetriever 实现：RetrievalPipeline + DocumentAggregationService 组合。
 *
 * <p>调用链：
 * <pre>
 *   query
 *     → RetrievalPipeline.search()（vector / keyword / hybrid+RRF，documentIds 全程贯穿）
 *     → RetrievedChunk[]
 *     → DocumentAggregationService.aggregate() → RetrievedDocument[]
 * </pre>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultDocumentRetriever implements DocumentRetriever {

    private final RetrievalPipeline retrievalPipeline;
    private final DocumentAggregationService aggregationService;

    @Override
    public List<RetrievedDocument> search(String query, RetrievalOptions options) {
        List<RetrievedChunk> chunks = retrievalPipeline.search(query, options);
        return aggregationService.aggregate(chunks);
    }
}
