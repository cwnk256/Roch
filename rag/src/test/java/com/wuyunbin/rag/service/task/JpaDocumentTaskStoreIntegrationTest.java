package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.DocumentTask;
import com.wuyunbin.rag.dto.DocumentTaskType;
import com.wuyunbin.rag.persistence.task.DocumentTaskJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@SpringBootTest
@Testcontainers
class JpaDocumentTaskStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("roch")
            .withUsername("roch")
            .withPassword("roch");
    @Autowired
    private JpaDocumentTaskStore store;
    @Autowired
    private DocumentTaskJpaRepository repository;
    private String taskId;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterEach
    void cleanUp() {
        if (taskId != null) {
            repository.findByTaskId(taskId).ifPresent(repository::delete);
        }
    }

    @Test
    void updateAfterReadingPersistsTaskWithDocumentIds() {
        DocumentTask task = DocumentTask.create(
                DocumentTaskType.SUMMARY, List.of("document-a"), "integration test");
        taskId = task.getTaskId();
        store.save(task);

        DocumentTask loaded = store.find(taskId).orElseThrow();
        loaded.markRunning();

        assertDoesNotThrow(() -> store.update(loaded));
    }
}
