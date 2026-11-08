package com.wuyunbin.rag.dto;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetrievalOptions 行为测试：documentIds 过滤判断 + 取值。
 */
class RetrievalOptionsTest {

    @Test
    void hasDocumentFilter_nullIds_false() {
        RetrievalOptions options = new RetrievalOptions(8, 0.5, null);
        assertFalse(options.hasDocumentFilter());
    }

    @Test
    void hasDocumentFilter_emptyIds_false() {
        RetrievalOptions options = new RetrievalOptions(8, 0.5, Set.of());
        assertFalse(options.hasDocumentFilter());
    }

    @Test
    void hasDocumentFilter_nonEmptyIds_true() {
        RetrievalOptions options = new RetrievalOptions(8, 0.5, Set.of("doc-a"));
        assertTrue(options.hasDocumentFilter());
    }

    @Test
    void hasDocumentFilter_multiIds_true() {
        RetrievalOptions options = new RetrievalOptions(8, 0.5, Set.of("doc-a", "doc-b"));
        assertTrue(options.hasDocumentFilter());
        assertEquals(2, options.documentIds().size());
    }

    @Test
    void values_preserved() {
        RetrievalOptions options = new RetrievalOptions(10, 0.7, Set.of("doc-a"));
        assertEquals(10, options.topK());
        assertEquals(0.7, options.threshold(), 1e-9);
    }
}
