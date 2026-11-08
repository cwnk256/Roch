package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.DocumentTask;
import com.wuyunbin.rag.service.export.DocumentExporter;
import com.wuyunbin.rag.service.export.ExportedFile;
import com.wuyunbin.rag.service.generation.EvidenceIntegrityValidator;
import com.wuyunbin.rag.service.generation.model.GeneratedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentTaskRunner {
    private final DocumentTaskStore tasks;
    private final DocumentTaskExecutor generator;
    private final DocumentExporter exporter;
    private final GeneratedDocumentStorage results;
    private final TaskArtifactService artifacts;
    private final EvidenceIntegrityValidator evidenceValidator;
    @Qualifier("documentTaskAsyncExecutor")
    private final Executor executor;

    public void submit(String taskId) {
        executor.execute(() -> run(taskId));
    }

    public com.wuyunbin.rag.dto.DocumentArtifactResponse reExport(String taskId, GeneratedDocument document) {
        ExportedFile exported = exporter.export(document, taskId + "-reexport-" + UUID.randomUUID());
        return artifacts.register(taskId, exported);
    }

    void run(String taskId) {
        DocumentTask task = tasks.find(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        try {
            task.markRunning();
            task.updateProgress(20, "GENERATING");
            tasks.update(task);
            GeneratedDocument result = generator.execute(task);
            evidenceValidator.validate(result, new HashSet<>(task.getDocumentIds()));
            results.save(taskId, result);
            task.updateProgress(75, "EXPORTING");
            tasks.update(task);
            ExportedFile exported = exporter.export(result, taskId);
            artifacts.register(taskId, exported);
            task.markCompleted(result.title(), exported.path().toString(), exported.fileName());
            tasks.update(task);
        } catch (RuntimeException ex) {
            task.markFailed("文档生成失败，请稍后重试。");
            task.setErrorCode("TASK_EXECUTION_ERROR");
            tasks.update(task);
            log.error("文档任务失败 taskId={}", taskId, ex);
        }
    }
}
