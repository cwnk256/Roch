package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.DocumentTask;
import com.wuyunbin.rag.dto.DocumentTaskType;
import com.wuyunbin.rag.dto.DocumentTaskStatus;
import com.wuyunbin.rag.dto.DocumentTaskResponse;
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

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void recoversBothUnfinishedStatesAndKeepsTerminalHistory() {
        for (var status : DocumentTaskStatus.values()) {
            var task = DocumentTask.create(DocumentTaskType.REPORT, List.of("doc"), "recovery test");
            task.setStatus(status);
            task.updateProgress(37, status.name());
            store.save(task);
            store.failInterruptedTasks();
            var response = DocumentTaskResponse.from(store.find(task.getTaskId()).orElseThrow());
            boolean interrupted = status == DocumentTaskStatus.PENDING || status == DocumentTaskStatus.RUNNING;
            assertEquals(interrupted ? DocumentTaskStatus.FAILED : status, response.status());
            assertEquals(37, response.progress());
            assertEquals(response.status().name(), response.currentStep());
            assertEquals(interrupted ? "TASK_INTERRUPTED" : null, response.errorCode());
        }
    }

    @Test
    void onlyOneWorkerCanClaimPendingTask() {
        var task = DocumentTask.create(DocumentTaskType.REPORT, List.of("doc"), "claim");
        store.save(task);
        int claims = IntStream.range(0, 4).parallel()
                .map(i -> store.claimRunning(task.getTaskId())).sum();
        assertEquals(1, claims);
        assertNotNull(store.find(task.getTaskId()).orElseThrow().getStartedAt());
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
                DocumentTaskType.REPORT, List.of("document-a"), "integration test");
        task.setConfiguration(ReportTemplates.resolve(new com.wuyunbin.rag.dto.DocumentTaskRequest(
                DocumentTaskType.REPORT, task.getDocumentIds(), task.getInstruction(), "ANALYSIS", "1", null, null, null)));
        taskId = task.getTaskId();
        store.save(task);

        DocumentTask loaded = store.find(taskId).orElseThrow();
        assertEquals(task.getConfiguration(), loaded.getConfiguration());
        loaded.markRunning();

        assertDoesNotThrow(() -> store.update(loaded));
    }
}
