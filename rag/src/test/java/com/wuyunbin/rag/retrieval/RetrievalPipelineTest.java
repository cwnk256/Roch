package com.wuyunbin.rag.retrieval;

import com.wuyunbin.rag.dto.RetrievalOptions;
import com.wuyunbin.rag.dto.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RetrievalPipeline 单测：模式路由、documentIds 双路贯穿、单路异常降级、默认模式回退。
 */
class RetrievalPipelineTest {

    private ChunkRetriever vectorRetriever;
    private KeywordRetriever keywordRetriever;
    private RetrievalProperties properties;
    private RetrievalPipeline pipeline;

    private static RetrievalOptions options(RetrievalMode mode) {
        return new RetrievalOptions(10, 0.0, Set.of("docA", "docB"), mode);
    }

    private static RetrievedChunk chunk(String id) {
        return new RetrievedChunk(id, "docA", "f.txt", "c", null, null, null, 0, 0.8);
    }

    @BeforeEach
    void setUp() {
        vectorRetriever = mock(ChunkRetriever.class);
        keywordRetriever = mock(KeywordRetriever.class);
        properties = new RetrievalProperties();
        properties.setMode("vector");
        properties.setRrfK(60);
        properties.setVectorWeight(1.0);
        properties.setKeywordWeight(1.0);
        pipeline = new RetrievalPipeline(vectorRetriever, keywordRetriever, new RrfFuser(), properties);
    }

    @Test
    void vectorMode_callsOnlyVectorRetriever_preservesNativeScore() {
        when(vectorRetriever.search(eq("q"), any())).thenReturn(List.of(chunk("V1")));

        List<RetrievedChunk> result = pipeline.search("q", options(RetrievalMode.VECTOR));

        assertEquals(1, result.size());
        assertEquals("V1", result.get(0).chunkId());
        assertEquals(0.8, result.get(0).score(), 1e-9);
        verify(vectorRetriever).search(eq("q"), any());
        verifyNoInteractions(keywordRetriever);
    }

    @Test
    void keywordMode_callsOnlyKeywordRetriever() {
        when(keywordRetriever.search(eq("q"), any())).thenReturn(List.of(chunk("K1")));

        List<RetrievedChunk> result = pipeline.search("q", options(RetrievalMode.KEYWORD));

        assertEquals(1, result.size());
        assertEquals("K1", result.get(0).chunkId());
        verify(keywordRetriever).search(eq("q"), any());
        verifyNoInteractions(vectorRetriever);
    }

    @Test
    void hybridMode_callsBothWithSameOptions_passthroughDocumentIds() {
        when(vectorRetriever.search(eq("q"), any())).thenReturn(List.of(chunk("V1"), chunk("BOTH")));
        when(keywordRetriever.search(eq("q"), any())).thenReturn(List.of(chunk("BOTH"), chunk("K2")));

        List<RetrievedChunk> result = pipeline.search("q", options(RetrievalMode.HYBRID));

        // BOTH 双路命中 → RRF 第一
        assertEquals("BOTH", result.get(0).chunkId());
        assertEquals(1.0, result.get(0).score(), 1e-9);
        assertEquals(3, result.size());

        RetrievalOptions expected = options(RetrievalMode.HYBRID);
        verify(vectorRetriever).search(eq("q"), argThat(o ->
                o.documentIds().equals(expected.documentIds()) && o.topK() == 10));
        verify(keywordRetriever).search(eq("q"), argThat(o ->
                o.documentIds().equals(expected.documentIds()) && o.topK() == 10));
    }

    @Test
    void hybridMode_keywordFails_degradesToVector() {
        when(vectorRetriever.search(eq("q"), any())).thenReturn(List.of(chunk("V1")));
        when(keywordRetriever.search(eq("q"), any())).thenThrow(new RuntimeException("pg down"));

        List<RetrievedChunk> result = pipeline.search("q", options(RetrievalMode.HYBRID));

        assertEquals(List.of("V1"), result.stream().map(RetrievedChunk::chunkId).toList());
        // 降级走原始向量结果，保留原生 score
        assertEquals(0.8, result.get(0).score(), 1e-9);
    }

    @Test
    void hybridMode_vectorFails_degradesToKeyword() {
        when(vectorRetriever.search(eq("q"), any())).thenThrow(new RuntimeException("milvus down"));
        when(keywordRetriever.search(eq("q"), any())).thenReturn(List.of(chunk("K1")));

        List<RetrievedChunk> result = pipeline.search("q", options(RetrievalMode.HYBRID));

        assertEquals(List.of("K1"), result.stream().map(RetrievedChunk::chunkId).toList());
    }

    @Test
    void hybridMode_bothFail_throws() {
        when(vectorRetriever.search(any(), any())).thenThrow(new RuntimeException("milvus down"));
        when(keywordRetriever.search(any(), any())).thenThrow(new RuntimeException("pg down"));

        assertThrows(IllegalStateException.class,
                () -> pipeline.search("q", options(RetrievalMode.HYBRID)));
    }

    @Test
    void nullMode_fallsBackToGlobalConfig() {
        properties.setMode("keyword");
        when(keywordRetriever.search(eq("q"), any())).thenReturn(List.of(chunk("K1")));

        List<RetrievedChunk> result = pipeline.search("q",
                new RetrievalOptions(10, 0.0, null, null));

        assertEquals("K1", result.get(0).chunkId());
        verify(keywordRetriever).search(eq("q"), any());
        verifyNoInteractions(vectorRetriever);
    }
}
