package com.wuyunbin.rag.splitter;

import com.wuyunbin.rag.cleaning.ChunkingProperties;
import com.wuyunbin.rag.testutil.FakeEmbeddingModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SemanticTextSplitterTest {

    @Test
    void usesConfiguredChunkLimits() {
        ChunkingProperties properties = new ChunkingProperties();
        properties.setSimilarityThreshold(0.7);
        properties.setMinChunkSize(50);
        properties.setMaxChunkSize(1000);
        properties.setEmbedBatchSize(32);

        SemanticTextSplitter splitter = new SemanticTextSplitter(new FakeEmbeddingModel(), properties);

        assertEquals(50, splitter.getMinChunkSize());
        assertEquals(1000, splitter.getMaxChunkSize());
    }
}
