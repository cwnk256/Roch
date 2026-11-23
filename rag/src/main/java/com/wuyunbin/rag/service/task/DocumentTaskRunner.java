package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.DocumentTask;
import com.wuyunbin.rag.dto.DocumentTaskRequest;
import com.wuyunbin.rag.dto.DocumentArtifactResponse;
import com.wuyunbin.rag.dto.ReportExportOptions;
import com.wuyunbin.rag.desktop.DesktopChatOptions;
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
import java.nio.file.Path;

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

    public DocumentArtifactResponse reExport(String taskId, GeneratedDocument document, ReportExportOptions options, Path output) {
        String fileStem = taskId + "-reexport-" + UUID.randomUUID();
        ExportedFile exported = output == null ? exporter.export(document, fileStem, options)
                : exporter.export(document, fileStem, options, output);
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
            var snapshot = task.getGenerationSnapshot();
            var model = snapshot == null ? chatModel.getOptions().getModel() : snapshot.model();
            var output = snapshot == null ? null : Path.of(snapshot.outputDirectory());
            GeneratedDocument result = DesktopChatOptions.withReportSnapshot(snapshot, () -> generator.execute(task))
                    .withConfiguration(configuration.withGenerationMetadata(model, "traceable-report-v2.1"));
            evidenceValidator.validate(result, new HashSet<>(task.getDocumentIds()));
            if (output == null) results.save(taskId, result);
            else results.save(taskId, result, output);
            task.updateProgress(75, "EXPORTING");
            tasks.update(task);
            progressPublisher.publish(task);
            ExportedFile exported = output == null ? exporter.export(result, taskId)
                    : exporter.export(result, taskId, null, output);
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
