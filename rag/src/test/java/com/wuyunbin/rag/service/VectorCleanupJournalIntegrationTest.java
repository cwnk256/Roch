package com.wuyunbin.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@Testcontainers
class VectorCleanupJournalIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void recoversInterruptedWritesAndRetriesFailedCleanupWithoutDeletingActiveVectors() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V6__document_vector_cleanup.sql"))
                .execute(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        var transactions = new DataSourceTransactionManager(dataSource);
        var vectorStore = mock(VectorStore.class);
        var journal = new VectorCleanupJournal(jdbc, vectorStore, transactions, "default", "test");

        for (boolean rollback : List.of(true, false)) {
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('doc', 0))", Integer.class);
                journal.prepare("doc", List.of("new"), List.of("previous"));
                journal.retryPendingCleanup();
                verifyNoInteractions(vectorStore);
                if (rollback) transaction.setRollbackOnly();
            });
            var obsolete = List.of(rollback ? "new" : "previous");
            doThrow(new IllegalStateException("Milvus unavailable")).doNothing().when(vectorStore).delete(obsolete);
            journal.retryPendingCleanup();
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_vector_cleanup", Integer.class));
            journal.retryPendingCleanup();
            verify(vectorStore, times(2)).delete(obsolete);
            verifyNoMoreInteractions(vectorStore);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_vector_cleanup", Integer.class));
            reset(vectorStore);
        }
    }
}
