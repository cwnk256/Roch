package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.DocumentTask;
import com.wuyunbin.rag.dto.DocumentTaskRequest;
import com.wuyunbin.rag.dto.DocumentTaskType;
import com.wuyunbin.rag.dto.DocumentArtifactResponse;
import com.wuyunbin.rag.dto.ReportExportOptions;
import org.springframework.ai.chat.model.ChatModel;
import com.wuyunbin.rag.service.export.DocumentExporter;
import com.wuyunbin.rag.service.export.ExportedFile;
import com.wuyunbin.rag.service.generation.EvidenceIntegrityValidator;
import com.wuyunbin.rag.service.generation.OriginalPassages;
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
    private final ChatModel chatModel;
    private final DocumentExporter exporter;
    private final GeneratedDocumentStorage results;
    private final TaskArtifactService artifacts;
    private final EvidenceIntegrityValidator evidenceValidator;
    private final TaskProgressPublisher progressPublisher;
    private final OriginalPassages originals;
    @Qualifier("documentTaskAsyncExecutor")
    private final Executor executor;

    public void submit(String taskId) {
        executor.execute(() -> run(taskId));
    }

    public DocumentArtifactResponse reExport(String taskId, GeneratedDocument document, ReportExportOptions options) {
        ExportedFile exported = exporter.export(document, taskId + "-reexport-" + UUID.randomUUID(), options);
        return artifacts.register(taskId, exported);
    }

    void run(String taskId) {
        if (tasks.claimRunning(taskId) != 1) {
            log.info("[DocumentTask] taskId={} skipped because it was already claimed", taskId);
            return;
        }
        DocumentTask task = tasks.find(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        progressPublisher.publish(task);
        originals.beginTask();
        try {
            task.updateProgress(20, "GENERATING");
            tasks.update(task);
            progressPublisher.publish(task);
            var configuration = task.getConfiguration();
            if (configuration == null) {
                configuration = ReportTemplates.resolve(new DocumentTaskRequest(task.getType(), task.getDocumentIds(), task.getInstruction()));
            }
            String promptVersion = task.getType() == DocumentTaskType.COMPARE ? "traceable-report-v2.2" : "traceable-report-v2.1";
            GeneratedDocument result = generator.execute(task).withConfiguration(configuration.withGenerationMetadata(
                    chatModel.getOptions().getModel(), promptVersion));
            evidenceValidator.validate(result, new HashSet<>(task.getDocumentIds()));
            results.save(taskId, result);
            task.updateProgress(75, "EXPORTING");
            tasks.update(task);
            progressPublisher.publish(task);
            ExportedFile exported = exporter.export(result, taskId);
            artifacts.register(taskId, exported);
            task.markCompleted(result.title(), exported.path().toString(), exported.fileName());
            tasks.update(task);
            progressPublisher.publish(task);
        } catch (RuntimeException ex) {
            task.markFailed("文档生成失败，请稍后重试。");
            task.setErrorCode("TASK_EXECUTION_ERROR");
            tasks.update(task);
            progressPublisher.publish(task);
            log.error("文档任务失败 taskId={}", taskId, ex);
        } finally {
            originals.endTask();
        }
    }
}
