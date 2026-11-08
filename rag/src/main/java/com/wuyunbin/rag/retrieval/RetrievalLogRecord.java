package com.wuyunbin.rag.retrieval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 一次 similaritySearch 调用产生的日志记录。
 * 命中切片作为 hits 数组内嵌，一次检索只写一行。
 */
public record RetrievalLogRecord(
        String logId,
        String timestamp,
        String conversationId,
        Integer round,
        String query,
        int queryLength,
        Integer topK,
        Double similarityThreshold,
        String filterExpression,
        String collection,
        String database,
        int hitCount,
        long latencyMs,
        String outcome,
        boolean usedInPrompt,
        boolean degraded,
        String errorMessage,
        List<RetrievalHit> hits
) {

    private static final String COLLECTION = "rag_docs";
    private static final String DATABASE = "default";
    private static final int MAX_ERROR_LEN = 500;

    /**
     * 未启用检索。
     */
    public static RetrievalLogRecord skipped(String conversationId, Integer round, String query) {
        return new RetrievalLogRecord(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                conversationId, round,
                query, query.length(),
                null, null, null,
                COLLECTION, DATABASE,
                0, 0L,
                "SKIPPED", false, false,
                null, List.of()
        );
    }

    /**
     * 检索有命中或无命中。
     */
    public static RetrievalLogRecord of(String conversationId, Integer round, String query,
                                        int topK, double threshold, long latencyMs,
                                        List<RetrievalHit> hits, boolean usedInPrompt) {
        String outcome = hits.isEmpty() ? "EMPTY" : "HIT";
        return new RetrievalLogRecord(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                conversationId, round,
                query, query.length(),
                topK, threshold, null,
                COLLECTION, DATABASE,
                hits.size(), latencyMs,
                outcome, usedInPrompt, false,
                null, hits
        );
    }

    /**
     * 检索抛异常，降级为普通问答。
     */
    public static RetrievalLogRecord error(String conversationId, Integer round, String query,
                                           int topK, double threshold, long latencyMs,
                                           RuntimeException e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (msg.length() > MAX_ERROR_LEN) {
            msg = msg.substring(0, MAX_ERROR_LEN);
        }
        return new RetrievalLogRecord(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                conversationId, round,
                query, query.length(),
                topK, threshold, null,
                COLLECTION, DATABASE,
                0, latencyMs,
                "ERROR", false, true,
                msg, List.of()
        );
    }
}
