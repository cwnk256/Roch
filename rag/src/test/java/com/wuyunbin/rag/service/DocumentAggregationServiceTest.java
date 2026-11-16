package com.wuyunbin.rag.service;

import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.dto.RetrievedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档聚合算法单元测试：验证 max + hitCount 加成 + 排序 + 上限。
 */
class DocumentAggregationServiceTest {

    private final DocumentAggregationService service = new DocumentAggregationService();

    private static RetrievedChunk chunk(String docId, String name, double score, int idx) {
        return new RetrievedChunk("cid-" + docId + "-" + idx, docId, name, "content-" + idx,
                null, null, null, idx, score);
    }

    @Test
    void aggregate_empty_returnsEmpty() {
        assertTrue(service.aggregate(List.of()).isEmpty());
        assertTrue(service.aggregate(null).isEmpty());
    }

    @Test
    void aggregate_singleDocSingleChunk_scoreEqualsMax() {
        List<RetrievedChunk> chunks = List.of(chunk("docA", "a.pdf", 0.92, 0));
        List<RetrievedDocument> docs = service.aggregate(chunks);
        assertEquals(1, docs.size());
        assertEquals("docA", docs.get(0).documentId());
        assertEquals(0.92, docs.get(0).score(), 1e-9);
        assertEquals(1, docs.get(0).hitCount());
    }

    @Test
    void aggregate_multiChunksSameDoc_hitCountBonus() {
        // docA: 3 个切片 0.95 / 0.90 / 0.82
        // max = 0.95, hitCount = 3, bonus = min(3-1, 4) * 0.02 = 0.04
        // docScore = 0.95 + 0.04 = 0.99
        List<RetrievedChunk> chunks = List.of(
                chunk("docA", "a.pdf", 0.95, 0),
                chunk("docA", "a.pdf", 0.90, 1),
                chunk("docA", "a.pdf", 0.82, 2)
        );
        List<RetrievedDocument> docs = service.aggregate(chunks);
        assertEquals(1, docs.size());
        assertEquals(0.99, docs.get(0).score(), 1e-9);
        assertEquals(3, docs.get(0).hitCount());
        // matchedChunks 按相似度降序
        assertEquals(0.95, docs.get(0).matchedChunks().get(0).score(), 1e-9);
        assertEquals(0.90, docs.get(0).matchedChunks().get(1).score(), 1e-9);
        assertEquals(0.82, docs.get(0).matchedChunks().get(2).score(), 1e-9);
    }

    @Test
    void aggregate_multiDocs_sortedByScoreDesc() {
        // docA: 1 个切片 0.95 → score = 0.95
        // docB: 1 个切片 0.91 → score = 0.91
        // 排序后 docA 应在前
        List<RetrievedChunk> chunks = List.of(
                chunk("docA", "a.pdf", 0.95, 0),
                chunk("docB", "b.pdf", 0.91, 0)
        );
        List<RetrievedDocument> docs = service.aggregate(chunks);
        assertEquals(2, docs.size());
        assertEquals("docA", docs.get(0).documentId());
        assertEquals("docB", docs.get(1).documentId());
    }

    @Test
    void aggregate_moreHitsLowerMax_canOvertake() {
        // docA: 1 个切片 0.95 → score = 0.95
        // docB: 5 个切片 最高 0.94 → score = 0.94 + min(4,4)*0.02 = 0.94 + 0.08 = 1.0
        // docB 反超 docA
        List<RetrievedChunk> chunks = List.of(
                chunk("docA", "a.pdf", 0.95, 0),
                chunk("docB", "b.pdf", 0.94, 0),
                chunk("docB", "b.pdf", 0.85, 1),
                chunk("docB", "b.pdf", 0.80, 2),
                chunk("docB", "b.pdf", 0.78, 3),
                chunk("docB", "b.pdf", 0.75, 4)
        );
        List<RetrievedDocument> docs = service.aggregate(chunks);
        assertEquals("docB", docs.get(0).documentId());
        assertEquals(1.0, docs.get(0).score(), 1e-9);
        assertEquals(5, docs.get(0).hitCount());
    }

    @Test
    void aggregate_scoreCappedAt1() {
        // 10 个切片 最高 0.99 → bonus = min(9, 4) * 0.02 = 0.08
        // 0.99 + 0.08 = 1.07 → 应 cap 到 1.0
        List<RetrievedChunk> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            chunks.add(chunk("docA", "a.pdf", 0.99 - i * 0.005, i));
        }
        List<RetrievedDocument> docs = service.aggregate(chunks);
        assertEquals(1.0, docs.get(0).score(), 1e-9);
    }

    @Test
    void aggregate_chunkWithNullDocId_skipped() {
        List<RetrievedChunk> chunks = List.of(
                chunk("docA", "a.pdf", 0.9, 0),
                new RetrievedChunk("cid", null, "x", "c", null, null, null, 0, 0.5)
        );
        List<RetrievedDocument> docs = service.aggregate(chunks);
        assertEquals(1, docs.size());
        assertEquals("docA", docs.get(0).documentId());
    }
}
