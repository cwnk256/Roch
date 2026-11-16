package com.wuyunbin.rag.service;

import com.wuyunbin.rag.cleaning.ChunkingProperties;
import com.wuyunbin.rag.cleaning.CleaningTextSplitter;
import com.wuyunbin.rag.dto.DocumentResponse;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
import com.wuyunbin.rag.service.DocumentService.ImportDecision;
import com.wuyunbin.rag.service.storage.LocalDocumentStorage;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.DeleteParam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.DigestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class DocumentServiceTest {

    @TempDir
    Path storageRoot;

    private CleaningTextSplitter cleaningTextSplitter;
    private VectorStore vectorStore;
    private MilvusServiceClient milvusClient;
    private ChunkingProperties chunkingProperties;
    private WordDocumentParser wordDocumentParser;
    private PdfDocumentParser pdfDocumentParser;
    private ExcelDocumentParser excelDocumentParser;
    private DocumentChunkJpaRepository chunkRepository;
    private ChunkTokenizer chunkTokenizer;
    private VectorCleanupJournal cleanupJournal;

    @BeforeEach
    void setUp() {
        cleaningTextSplitter = mock(CleaningTextSplitter.class);
        vectorStore = mock(VectorStore.class);
        milvusClient = mock(MilvusServiceClient.class);
        chunkingProperties = new ChunkingProperties();
        chunkingProperties.setPipelineVersion("legacy-v1");
        wordDocumentParser = mock(WordDocumentParser.class);
        pdfDocumentParser = mock(PdfDocumentParser.class);
        excelDocumentParser = mock(ExcelDocumentParser.class);
        chunkRepository = mock(DocumentChunkJpaRepository.class);
        chunkTokenizer = mock(ChunkTokenizer.class);
        cleanupJournal = mock(VectorCleanupJournal.class);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    /**
     * 用匿名子类替代 hasChunks 的 Milvus 查询，避免网络依赖，
     * 并区分"同 MD5+版本"与"仅 MD5"两次存在性查询。
     */
    private DocumentService serviceWithCounts(long sameVersionCount, long anyVersionCount) {
        DocumentService service = new DocumentService(cleaningTextSplitter, vectorStore, milvusClient, chunkingProperties, wordDocumentParser, pdfDocumentParser, excelDocumentParser, new LocalDocumentStorage(storageRoot.toString()), chunkRepository, chunkTokenizer, cleanupJournal) {
            @Override
            boolean hasChunks(String expr) {
                return (expr.contains("chunk_pipeline_version") ? sameVersionCount : anyVersionCount) > 0;
            }

            @Override
            List<String> vectorIds(String md5) {
                return List.of("previous-vector");
            }
        };
        ReflectionTestUtils.setField(service, "collectionName", "rag_docs");
        ReflectionTestUtils.setField(service, "databaseName", "default");
        return service;
    }

    private MockMultipartFile txtFile() {
        return new MockMultipartFile("file", "doc.txt", "text/plain",
                "正文内容，足够判定非空。".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void forcedReindexUsesStoredBytesEvenForCurrentVersion() throws Exception {
        var source = txtFile();
        var stored = new LocalDocumentStorage(storageRoot.toString());
        String id = DigestUtils.md5DigestAsHex(source.getBytes());
        stored.save(id, source.getOriginalFilename(), source.getBytes());
        DocumentService service = spy(new DocumentService(cleaningTextSplitter, vectorStore, milvusClient,
                chunkingProperties, wordDocumentParser, pdfDocumentParser, excelDocumentParser,
                stored, chunkRepository, chunkTokenizer, cleanupJournal));
        ReflectionTestUtils.setField(service, "collectionName", "rag_docs");
        ReflectionTestUtils.setField(service, "databaseName", "default");
        doReturn(true).when(service).hasChunks(anyString());
        doReturn(List.of("previous-vector")).when(service).vectorIds(id);
        when(cleaningTextSplitter.apply(anyList())).thenReturn(List.of(new Document("replacement")));
        var response = service.reindex(id, null);
        assertEquals(1, response.chunkCount());
        assertFalse(response.duplicated());
        verify(vectorStore).add(anyList());
    }

    @Test
    void malformedReplacementPreservesBothIndexes() throws Exception {
        var invalid = new MockMultipartFile("file", "invalid.pdf", "application/pdf", new byte[]{1, 2});
        DocumentService service = serviceWithCounts(1, 1);
        when(pdfDocumentParser.parsePages(any())).thenThrow(new IOException("malformed"));
        String id = DigestUtils.md5DigestAsHex(invalid.getBytes());
        assertThrows(RuntimeException.class, () -> service.reindex(id, invalid));
        verify(milvusClient, never()).delete(any(DeleteParam.class));
        verify(chunkRepository, never()).deleteByDocMd5(anyString());
    }

    @Test
    void sameMd5SameVersionSkips() {
        DocumentService service = serviceWithCounts(1, 1);
        assertEquals(ImportDecision.SKIP_CURRENT, service.resolveImportDecision("md5", "legacy-v1"));
    }

    @Test
    void sameMd5DifferentVersionRebuilds() {
        DocumentService service = serviceWithCounts(0, 1);
        assertEquals(ImportDecision.REBUILD_NEW_VERSION, service.resolveImportDecision("md5", "legacy-v2"));
    }

    @Test
    void unknownMd5ImportsAsNewDocument() {
        DocumentService service = serviceWithCounts(0, 0);
        assertEquals(ImportDecision.NEW_DOCUMENT, service.resolveImportDecision("md5", "legacy-v1"));
    }

    @Test
    void rebuildWritesNewVersionThenDeletesOld() {
        DocumentService service = serviceWithCounts(0, 1);
        when(cleaningTextSplitter.apply(anyList()))
                .thenReturn(List.of(new Document("块一", Map.of()), new Document("块二", Map.of())));

        DocumentResponse response = service.importTxt(txtFile());

        assertEquals(2, response.chunkCount());
        assertFalse(response.duplicated());
        verify(vectorStore).add(anyList());
        verify(cleanupJournal).prepare(anyString(), anyList(), eq(List.of("previous-vector")));
        verify(cleanupJournal, never()).clean(any());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(cleanupJournal).clean(any());
    }

    @Test
    void postgresWriteFailurePreservesPreviousVectors() {
        DocumentService service = serviceWithCounts(0, 1);
        when(cleaningTextSplitter.apply(anyList())).thenReturn(List.of(new Document("replacement")));
        when(chunkRepository.saveAllAndFlush(anyList())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> service.importTxt(txtFile()));
        verifyNoInteractions(vectorStore);
    }

    @Test
    void partialVectorWriteOnlyCleansUpTheAttemptedBatch() {
        DocumentService service = serviceWithCounts(0, 1);
        var replacement = new Document("replacement");
        when(cleaningTextSplitter.apply(anyList())).thenReturn(List.of(replacement));
        UUID batch = UUID.randomUUID();
        when(cleanupJournal.prepare(anyString(), eq(List.of(replacement.getId())), eq(List.of("previous-vector")))).thenReturn(batch);
        doThrow(new IllegalStateException("vector write failed")).when(vectorStore).add(anyList());
        assertThrows(IllegalStateException.class, () -> service.importTxt(txtFile()));
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(cleanupJournal).clean(batch);
    }

    @Test
    void duplicateSkipsVectorStoreAdd() {
        DocumentService service = serviceWithCounts(1, 1);

        DocumentResponse response = service.importTxt(txtFile());

        assertEquals(0, response.chunkCount());
        assertTrue(response.duplicated());
        verify(vectorStore, never()).add(anyList());
        verify(cleaningTextSplitter, never()).apply(anyList());
    }

    @Test
    void newDocumentImportsWithoutDelete() {
        DocumentService service = serviceWithCounts(0, 0);
        when(cleaningTextSplitter.apply(anyList())).thenReturn(List.of(new Document("块", Map.of())));

        DocumentResponse response = service.importTxt(txtFile());

        assertEquals(1, response.chunkCount());
        assertFalse(response.duplicated());
        verify(vectorStore).add(anyList());
        verify(milvusClient, never()).delete(any(DeleteParam.class));
    }
}
