package com.wuyunbin.rag.service.ocr;

import com.wuyunbin.rag.dto.DocumentResponse;
import com.wuyunbin.rag.service.storage.DocumentStorage.StoredDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class OcrImportJobStoreIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void deduplicatesAndRestoresInterruptedJobsFromStagedPdf() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V5__document_chunks.sql"),
                new ClassPathResource("db/migration/V8__ocr_pdf_imports.sql"),
                new ClassPathResource("db/migration/V9__ocr_index_publication.sql")
        ).execute(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var jobs = new OcrImportJobStore(jdbc);
        var publication = new OcrIndexPublication(new NamedParameterJdbcTemplate(jdbc));
        String documentId = "0123456789abcdef0123456789abcdef";
        byte[] content = {1, 2, 3};
        var original = new StoredDocument(documentId, "扫描.pdf", content);

        var queued = transactions.execute(status -> {
            var pending = jobs.enqueue(original);
            publication.stageOriginal(documentId, false);
            return pending;
        });
        assertFalse(publication.originalPublished(documentId));
        var duplicate = transactions.execute(status -> jobs.enqueue(new StoredDocument(documentId, "别名.pdf", content)));
        assertEquals(queued.jobId(), duplicate.jobId());
        assertEquals(OcrImportJob.Status.QUEUED, queued.status());

        var firstAttempt = jobs.claimNext().orElseThrow();
        jobs.totalPages(firstAttempt.jobId(), 2);
        jobs.currentPage(firstAttempt.jobId(), 2);
        jobs.markIndexing(firstAttempt.jobId());
        assertEquals(OcrImportJob.Status.INDEXING, jobs.find(queued.jobId()).orElseThrow().status());

        var restarted = new OcrImportJobStore(jdbc);
        restarted.recoverInterrupted();
        var recovered = restarted.claimNext().orElseThrow();
        assertEquals("扫描.pdf", recovered.source().fileName());
        assertArrayEquals(content, recovered.source().content());
        restarted.totalPages(recovered.jobId(), 2);
        restarted.currentPage(recovered.jobId(), 2);
        restarted.fail(recovered.jobId(), true, "识别失败");
        var failed = restarted.find(queued.jobId()).orElseThrow();
        assertEquals(2, failed.failedPage());
        assertEquals(OcrImportJob.Status.FAILED, failed.status());
        assertEquals(OcrImportJob.Status.QUEUED, restarted.retry(queued.jobId()).orElseThrow().status());

        var lastAttempt = restarted.claimNext().orElseThrow();
        restarted.markIndexing(lastAttempt.jobId());
        var result = new DocumentResponse(1, documentId, false, "导入成功");
        var generation = UUID.randomUUID();
        Map<String, Object> vector = Map.of("doc_md5", documentId, OcrIndexPublication.GENERATION, generation.toString());
        assertTrue(publication.visible(List.of(vector), row -> row).isEmpty());
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO document_chunks (chunk_id, doc_md5, content, tokens) VALUES (?, ?, ?, ?)",
                    "chunk", documentId, "content", "content");
            publication.publish(documentId, generation);
            restarted.complete(lastAttempt.jobId(), result);
            assertTrue(CompletableFuture.supplyAsync(() -> publication.visible(List.of(vector), row -> row))
                    .join().isEmpty());
            assertFalse(CompletableFuture.supplyAsync(() -> publication.originalPublished(documentId)).join());
            status.setRollbackOnly();
        });
        assertEquals(OcrImportJob.Status.INDEXING, restarted.find(queued.jobId()).orElseThrow().status());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_chunks", Integer.class));
        assertArrayEquals(content, jdbc.queryForObject("SELECT source_pdf FROM ocr_pdf_imports WHERE job_id = ?",
                byte[].class, queued.jobId()));

        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO document_chunks (chunk_id, doc_md5, content, tokens) VALUES (?, ?, ?, ?)",
                    "chunk", documentId, "content", "content");
            publication.publish(documentId, generation);
            restarted.complete(lastAttempt.jobId(), result);
        });
        assertEquals(List.of(vector), publication.visible(List.of(vector), row -> row));
        assertTrue(publication.originalPublished(documentId));
        var replacement = UUID.randomUUID();
        Map<String, Object> replacementVector = Map.of("doc_md5", documentId, OcrIndexPublication.GENERATION, replacement.toString());
        Map<String, Object> previousNative = Map.of("doc_md5", documentId, "chunk_pipeline_version", "native");
        assertEquals(List.of(vector), publication.visible(List.of(vector, replacementVector, previousNative), row -> row));
        var committed = new Document("committed", vector);
        var staged = new Document("staged", replacementVector);
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenAnswer(call -> {
            SearchRequest search = call.getArgument(0);
            assertEquals("原来的查询", search.getQuery());
            assertTrue(search.hasFilterExpression());
            return search.getTopK() == 1 ? List.of(staged) : List.of(staged, committed);
        });
        var request = SearchRequest.builder().query("原来的查询").topK(1)
                .filterExpression("doc_md5 == '" + documentId + "'").build();
        assertEquals(List.of(committed), publication.search(store, request));
        verify(store, times(2)).similaritySearch(any(SearchRequest.class));
        assertEquals(result, restarted.find(queued.jobId()).orElseThrow().result());
        assertNull(jdbc.queryForObject("SELECT source_pdf FROM ocr_pdf_imports WHERE job_id = ?", byte[].class, queued.jobId()));
        assertEquals(OcrImportJob.Status.SUCCEEDED, transactions.execute(status -> restarted.enqueue(original)).status());

        publication.unpublishGeneration(documentId);
        // A later legacy upload wrote native chunks only. They cannot satisfy a new OCR upload.
        assertEquals(List.of(previousNative), publication.visible(List.of(vector, previousNative), row -> row));
        assertTrue(publication.originalPublished(documentId));
        var reimported = transactions.execute(status -> restarted.enqueue(original));
        assertEquals(queued.jobId(), reimported.jobId());
        assertEquals(OcrImportJob.Status.QUEUED, reimported.status());
        assertArrayEquals(content, restarted.claimNext().orElseThrow().source().content());
    }
}
