package com.wuyunbin.rag.service;

import com.wuyunbin.rag.dto.DocumentSearchRequest;
import com.wuyunbin.rag.dto.DocumentSearchResponse;
import com.wuyunbin.rag.dto.RetrievalOptions;
import com.wuyunbin.rag.dto.RetrievedDocument;
import com.wuyunbin.rag.retrieval.DocumentRetriever;
import com.wuyunbin.rag.retrieval.RetrievalMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文档搜索编排服务：把请求 DTO 转 RetrievalOptions，调用 DocumentRetriever，组装响应。
 *
 * <p>默认 topK / threshold 复用 {@code rag.chat.rag.top-k} / {@code rag.chat.rag.similarity-threshold}，
 * 不引入新的配置项。</p>
 *
 * <p>检索失败时降级返回空结果（不抛 500），与 MultiTurnChatService 检索降级策略一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentSearchService {

    private final DocumentRetriever documentRetriever;

    @Value("${rag.chat.rag.top-k:8}")
    private int defaultTopK;

    @Value("${rag.chat.rag.similarity-threshold:0.5}")
    private double defaultThreshold;

    private static int clampTopK(int k) {
        return Math.max(1, Math.min(50, k));
    }

    private static double clampThreshold(double t) {
        return Math.max(0.0, Math.min(1.0, t));
    }

    private static void validate(DocumentSearchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (request.query().length() > 4000) {
            throw new IllegalArgumentException("query 长度不能超过 4000 字符");
        }
        if (request.topK() != null && request.topK() < 1) {
            throw new IllegalArgumentException("topK 必须 >= 1");
        }
        if (request.threshold() != null && (request.threshold() < 0.0 || request.threshold() > 1.0)) {
            throw new IllegalArgumentException("threshold 必须在 [0.0, 1.0] 范围内");
        }
        // mode 非法时 fromString 抛 IllegalArgumentException，由 Controller 转 400
        if (request.mode() != null) {
            RetrievalMode.fromString(request.mode());
        }
    }

    /**
     * 文档搜索：参数校验 → 检索 → 聚合 → 响应组装。
     * 检索异常时返回空文档列表（query 仍透传），不向调用方抛异常。
     */
    public DocumentSearchResponse search(DocumentSearchRequest request) {
        validate(request);
        int topK = request.topK() != null ? clampTopK(request.topK()) : defaultTopK;
        double threshold = request.threshold() != null ? clampThreshold(request.threshold()) : defaultThreshold;
        RetrievalMode mode = RetrievalMode.fromString(request.mode());
        RetrievalOptions options = new RetrievalOptions(topK, threshold, request.documentIds(), mode);

        long start = System.nanoTime();
        List<RetrievedDocument> documents;
        try {
            documents = documentRetriever.search(request.query(), options);
        } catch (RuntimeException e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("文档搜索检索失败 query={} latencyMs={} 转为空结果", request.query(), latencyMs, e);
            return new DocumentSearchResponse(request.query(), List.of());
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        log.info("文档搜索完成 mode={} query={} topK={} threshold={} docFilter={} 命中文档 {} 个，耗时 {}ms",
                mode != null ? mode : "default", request.query(), topK, threshold,
                options.hasDocumentFilter() ? options.documentIds().size() : 0,
                documents.size(), latencyMs);
        return new DocumentSearchResponse(request.query(), documents);
    }
}
