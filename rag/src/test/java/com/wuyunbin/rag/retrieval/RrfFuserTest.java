package com.wuyunbin.rag.retrieval;

import com.wuyunbin.rag.dto.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RrfFuser 单测：RRF 排名公式、权重、去重合并、归一化、topK 与稳定性。
 */
class RrfFuserTest {

    private final RrfFuser fuser = new RrfFuser();

    private static RetrievedChunk chunk(String id, Integer pageStart) {
        return new RetrievedChunk(id, "doc-" + id, "f.txt", "content-" + id,
                null, pageStart, null, 0, 0.0);
    }

    @Test
    void fuse_commonChunkInBothLists_ranksFirstAndScoresOne() {
        // 向量路 [A, B, C]，关键词路 [B, D]：B 在两路均 r1，RRF 分最高
        List<RetrievedChunk> vector = List.of(chunk("A", null), chunk("B", null), chunk("C", null));
        List<RetrievedChunk> keyword = List.of(chunk("B", 3), chunk("D", null));

        List<RetrievedChunk> fused = fuser.fuse(
                List.of(new RrfFuser.RankedList(vector, 1.0),
                        new RrfFuser.RankedList(keyword, 1.0)),
                60, 10);

        assertEquals(4, fused.size());
        assertEquals("B", fused.get(0).chunkId());
        assertEquals(1.0, fused.get(0).score(), 1e-9);
        // 归一化后其余分数严格小于 1 且降序
        for (int i = 1; i < fused.size(); i++) {
            assertTrue(fused.get(i).score() < fused.get(i - 1).score()
                    || fused.get(i).score() == fused.get(i - 1).score());
        }
        // A(r1 向量 1/61) 应排在 D(r2 关键词 1/62) 之前
        assertEquals("A", fused.get(1).chunkId());
    }

    @Test
    void fuse_keywordHeavierWeight_keywordTopWins() {
        List<RetrievedChunk> vector = List.of(chunk("A", null));
        List<RetrievedChunk> keyword = List.of(chunk("B", null));

        List<RetrievedChunk> fused = fuser.fuse(
                List.of(new RrfFuser.RankedList(vector, 1.0),
                        new RrfFuser.RankedList(keyword, 10.0)),
                60, 10);

        assertEquals("B", fused.get(0).chunkId());
        assertEquals(1.0, fused.get(0).score(), 1e-9);
    }

    @Test
    void fuse_duplicateChunk_mergesMetadataFromOtherList() {
        // 向量路 A 无页码，关键词路 A 带 pageStart=5；去重后保留一条且页码补齐
        RetrievedChunk vectorA = chunk("A", null);
        RetrievedChunk keywordA = chunk("A", 5);

        List<RetrievedChunk> fused = fuser.fuse(
                List.of(new RrfFuser.RankedList(List.of(vectorA), 1.0),
                        new RrfFuser.RankedList(List.of(keywordA), 1.0)),
                60, 10);

        assertEquals(1, fused.size());
        assertEquals("A", fused.get(0).chunkId());
        assertEquals(5, fused.get(0).pageStart());
    }

    @Test
    void fuse_equalScores_stableVectorFirst() {
        // A 仅向量 r1，X 仅关键词 r1，权重相同 → RRF 同分，插入顺序（向量在前）决定先后
        List<RetrievedChunk> vector = List.of(chunk("A", null));
        List<RetrievedChunk> keyword = List.of(chunk("X", null));

        List<RetrievedChunk> fused = fuser.fuse(
                List.of(new RrfFuser.RankedList(vector, 1.0),
                        new RrfFuser.RankedList(keyword, 1.0)),
                60, 10);

        assertEquals(List.of("A", "X"), fused.stream().map(RetrievedChunk::chunkId).toList());
    }

    @Test
    void fuse_respectsTopK() {
        List<RetrievedChunk> vector = List.of(chunk("A", null), chunk("B", null), chunk("C", null));
        List<RetrievedChunk> fused = fuser.fuse(
                List.of(new RrfFuser.RankedList(vector, 1.0)), 60, 2);
        assertEquals(2, fused.size());
        assertEquals(List.of("A", "B"), fused.stream().map(RetrievedChunk::chunkId).toList());
    }

    @Test
    void fuse_emptyOrNullInputs_returnsEmpty() {
        assertEquals(List.of(), fuser.fuse(List.of(), 60, 10));
        assertEquals(List.of(), fuser.fuse(
                List.of(new RrfFuser.RankedList(List.of(), 1.0),
                        new RrfFuser.RankedList(null, 1.0)), 60, 10));
    }
}
