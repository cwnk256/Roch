package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.DocumentTask;
import com.wuyunbin.rag.dto.DocumentTaskType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryDocumentTaskStoreTest {

    @Test
    void claimsPendingTaskOnlyOnce() {
        InMemoryDocumentTaskStore store = new InMemoryDocumentTaskStore();
        DocumentTask task = DocumentTask.create(DocumentTaskType.SUMMARY, List.of("document-1"), "summarize");
        store.save(task);

        assertEquals(1, store.claimRunning(task.getTaskId()));
        assertEquals(0, store.claimRunning(task.getTaskId()));
    }
}
