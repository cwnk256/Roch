package com.wuyunbin.rag.service;

import com.wuyunbin.rag.dto.DocumentSearchRequest;
import com.wuyunbin.rag.dto.DocumentSearchResponse;
import com.wuyunbin.rag.dto.RetrievalOptions;
import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.retrieval.ChunkRetriever;
import com.wuyunbin.rag.retrieval.DefaultDocumentRetriever;
import com.wuyunbin.rag.retrieval.KeywordRetriever;
import com.wuyunbin.rag.retrieval.RetrievalPipeline;
import com.wuyunbin.rag.retrieval.RetrievalProperties;
import com.wuyunbin.rag.retrieval.RrfFuser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DocumentSearchService 单测：验证参数透传、默认值、降级与结果组装。
 */
class DocumentSearchServiceTest {

    private ChunkRetriever chunkRetriever;
    private KeywordRetriever keywordRetriever;
    private DocumentSearchService service;

    @BeforeEach
    void setUp() {
        chunkRetriever = mock(ChunkRetriever.class);
        keywordRetriever = mock(KeywordRetriever.class);
        DocumentAggregationService agg = new DocumentAggregationService();
        RetrievalProperties properties = new RetrievalProperties();
        properties.setMode("vector");
        RetrievalPipeline pipeline = new RetrievalPipeline(
                chunkRetriever, keywordRetriever, new RrfFuser(), properties);
        DefaultDocumentRetriever retriever = new DefaultDocumentRetriever(pipeline, agg);
        service = new DocumentSearchService(retriever);
        ReflectionTestUtils.setField(service, "defaultTopK", 8);
        ReflectionTestUtils.setField(service, "defaultThreshold", 0.5);
    }

    @Test
    void search_validRequest_passesDefaults() {
        when(chunkRetriever.search(eq("query"), any())).thenReturn(List.of());
        service.search(new DocumentSearchRequest("query", null, null, null));

        ArgumentCaptor<RetrievalOptions> captor = ArgumentCaptor.forClass(RetrievalOptions.class);
        verify(chunkRetriever).search(eq("query"), captor.capture());
        RetrievalOptions options = captor.getValue();
        assertEquals(8, options.topK());
        assertEquals(0.5, options.threshold(), 1e-9);
        assertFalse(options.hasDocumentFilter());
    }

    @Test
    void search_withDocumentIds_passesFilter() {
        when(chunkRetriever.search(any(), any())).thenReturn(List.of());
        service.search(new DocumentSearchRequest("q", 5, 0.6, Set.of("docA")));

        ArgumentCaptor<RetrievalOptions> captor = ArgumentCaptor.forClass(RetrievalOptions.class);
        verify(chunkRetriever).search(eq("q"), captor.capture());
        RetrievalOptions options = captor.getValue();
        assertEquals(5, options.topK());
        assertEquals(0.6, options.threshold(), 1e-9);
        assertTrue(options.hasDocumentFilter());
        assertTrue(options.documentIds().contains("docA"));
    }

    @Test
    void search_emptyQuery_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.search(new DocumentSearchRequest("", null, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.search(new DocumentSearchRequest(null, null, null, null)));
    }

    @Test
    void search_invalidTopK_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.search(new DocumentSearchRequest("q", 0, null, null)));
    }

    @Test
    void search_invalidThreshold_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.search(new DocumentSearchRequest("q", null, 1.5, null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.search(new DocumentSearchRequest("q", null, -0.1, null)));
    }

    @Test
    void search_retrieverThrows_returnsEmptyResult() {
        when(chunkRetriever.search(any(), any())).thenThrow(new RuntimeException("milvus down"));
        DocumentSearchResponse response = service.search(new DocumentSearchRequest("q", null, null, null));
        assertEquals("q", response.query());
        assertTrue(response.documents().isEmpty());
    }

    @Test
    void search_returnsAggregatedDocuments() {
        RetrievedChunk c1 = new RetrievedChunk("c1", "docA", "a.pdf", "x", null, null, null, 0, 0.9);
        RetrievedChunk c2 = new RetrievedChunk("c2", "docB", "b.pdf", "y", null, null, null, 0, 0.85);
        when(chunkRetriever.search(any(), any())).thenReturn(List.of(c1, c2));

        DocumentSearchResponse response = service.search(new DocumentSearchRequest("q", null, null, null));
        assertEquals(2, response.documents().size());
        assertEquals("docA", response.documents().get(0).documentId());
        assertEquals(0.9, response.documents().get(0).score(), 1e-9);
        assertEquals("docB", response.documents().get(1).documentId());
    }

    @Test
    void search_hybridMode_invokesBothRetrieversWithSameFilter() {
        when(chunkRetriever.search(any(), any())).thenReturn(List.of());
        when(keywordRetriever.search(any(), any())).thenReturn(List.of());

        service.search(new DocumentSearchRequest("q", 5, 0.6, Set.of("docA"), "hybrid"));

        ArgumentCaptor<RetrievalOptions> vectorCaptor = ArgumentCaptor.forClass(RetrievalOptions.class);
        ArgumentCaptor<RetrievalOptions> keywordCaptor = ArgumentCaptor.forClass(RetrievalOptions.class);
        verify(chunkRetriever).search(eq("q"), vectorCaptor.capture());
        verify(keywordRetriever).search(eq("q"), keywordCaptor.capture());
        // documentIds 约束必须贯穿两路
        assertEquals(Set.of("docA"), vectorCaptor.getValue().documentIds());
        assertEquals(Set.of("docA"), keywordCaptor.getValue().documentIds());
    }

    @Test
    void search_invalidMode_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.search(new DocumentSearchRequest("q", null, null, null, "bm25")));
    }
}
