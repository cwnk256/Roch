package com.wuyunbin.rag.integration;

import com.wuyunbin.rag.cleaning.CleaningProperties;
import com.wuyunbin.rag.cleaning.TextNormalizer;
import com.wuyunbin.rag.dto.RetrievalOptions;
import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.retrieval.MilvusChunkRetriever;
import com.wuyunbin.rag.testutil.FakeEmbeddingModel;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.collection.DropCollectionParam;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Milvus 集成测试：验证 documentIds 过滤在向量库端生效。
 * 依赖本机 docker compose 启动的 Milvus（localhost:19530），测试使用独立 collection 并在结束后清理。
 */
class MilvusDocumentFilterIntegrationTest {

    private static final String QUERY = "maintenance schedule weekly inspection";
    private static final String COLLECTION = "rag_docs_it_" + UUID.randomUUID().toString().replace("-", "");

    private static MilvusServiceClient milvusClient;
    private static VectorStore vectorStore;

    @BeforeAll
    static void setUp() throws Exception {
        milvusClient = new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost("localhost")
                .withPort(19530)
                .build());
        MilvusVectorStore store = MilvusVectorStore.builder(milvusClient, new FakeEmbeddingModel())
                .databaseName("default")
                .collectionName(COLLECTION)
                .embeddingDimension(8)
                .initializeSchema(true)
                .build();
        store.afterPropertiesSet();
        vectorStore = store;

        vectorStore.add(List.of(
                chunk("doc-a", "A"),
                chunk("doc-b", "B"),
                chunk("doc-c", "C")));
    }

    @AfterAll
    static void tearDown() {
        if (milvusClient != null) {
            milvusClient.dropCollection(DropCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .build());
            milvusClient.close();
        }
    }

    private static Document chunk(String documentId, String suffix) {
        return Document.builder()
                .id(UUID.randomUUID().toString())
                .text(QUERY)
                .metadata(Map.of(
                        "doc_md5", documentId,
                        "chunk_id", "chunk-" + suffix,
                        "file_name", suffix + ".txt",
                        "chunk_index", 0))
                .build();
    }

    @Test
    void documentIdsFilterExcludesUnselectedDocumentsAtMilvus() {
        CleaningProperties cleaning = new CleaningProperties();
        cleaning.setEnabled(false);
        MilvusChunkRetriever retriever = new MilvusChunkRetriever(vectorStore, cleaning, new TextNormalizer());

        List<RetrievedChunk> allHits = retriever.search(QUERY,
                new RetrievalOptions(10, 0.0, Set.of()));
        assertEquals(Set.of("doc-a", "doc-b", "doc-c"),
                allHits.stream().map(RetrievedChunk::documentId).collect(java.util.stream.Collectors.toSet()));

        List<RetrievedChunk> filteredHits = retriever.search(QUERY,
                new RetrievalOptions(10, 0.0, Set.of("doc-a", "doc-b")));
        Set<String> filteredDocumentIds = filteredHits.stream()
                .map(RetrievedChunk::documentId)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of("doc-a", "doc-b"), filteredDocumentIds);
        assertTrue(filteredDocumentIds.stream().noneMatch("doc-c"::equals));
    }
}
