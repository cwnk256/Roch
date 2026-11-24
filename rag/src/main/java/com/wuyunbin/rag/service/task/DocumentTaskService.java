package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.*;
import com.wuyunbin.rag.service.export.DocumentExporter;
import com.wuyunbin.rag.service.generation.model.GeneratedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 文档任务服务：负责任务生命周期（校验 → 创建 → 执行 → 导出 → 终态），
 * 实际的"检索 + 生成"编排在 {@link DocumentTaskExecutor}，文件渲染在 {@link DocumentExporter}。
 *
 * <p>创建后立即返回 PENDING 快照，由后台执行器生成文档并更新任务状态。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentTaskService {

    private final DocumentTaskValidator validator;
    private final DocumentTaskStore taskStore;
    private final GeneratedDocumentStorage generatedDocumentStorage;
    private final TaskArtifactService artifactService;
    private final DocumentTaskRunner runner;
    private final DocumentTaskProperties properties;

    /**
     * 创建文档任务并提交给后台执行器。
     *
     * @throws IllegalArgumentException       参数非法（400）
     * @throws IllegalStateException          指定文档不存在或未就绪（404）
     */
    public DocumentTaskResponse createAndExecute(DocumentTaskRequest request) {
        // 1. 参数校验
        validator.validateRequest(request);
        ReportConfiguration configuration = ReportTemplates.resolve(request).withRetrieval(properties.snapshotRetrieval());
        // 2. 资料范围校验：先校验再建任务，非法引用不产生残留任务
        validator.validateDocumentsExist(request.documentIds());

        // 3. 创建任务（PENDING）
        DocumentTask task = DocumentTask.create(request.type(), request.documentIds(), request.instruction());
        task.setConfiguration(configuration);
        return enqueue(task);
    }

    private DocumentTaskResponse enqueue(DocumentTask task) {
        taskStore.save(task);
        log.info("[DocumentTask] taskId={} type={} documents={} instructionLength={} status=PENDING",
                task.getTaskId(), task.getType(), task.getDocumentIds().size(),
                task.getInstruction().length());

        var pending = DocumentTaskResponse.from(task);
        runner.submit(task.getTaskId());
        return pending;
    }

    /**
     * 按 ID 查询任务。
     */
    public Optional<DocumentTask> find(String taskId) {
        return taskStore.find(taskId);
    }

    public PageResult<DocumentTask> list(int page, int size) {
        return taskStore.list(page, size);
    }

    public DocumentTaskResponse retry(String taskId) {
        DocumentTask old = find(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        if (old.getStatus() != com.wuyunbin.rag.dto.DocumentTaskStatus.FAILED)
            throw new IllegalStateException("仅 FAILED 任务可重试");
        validator.validateDocumentsExist(old.getDocumentIds());
        DocumentTask retry = DocumentTask.create(old.getType(), old.getDocumentIds(), old.getInstruction());
        retry.setConfiguration(old.getConfiguration());
        return enqueue(retry);
    }

    public GeneratedDocument readResult(String taskId) {
        return generatedDocumentStorage.read(taskId);
    }

    public java.util.List<DocumentArtifactResponse> artifacts(String taskId) {
        return artifactService.list(taskId);
    }

    public java.nio.file.Path artifactPath(String taskId, String artifactId) {
        return artifactService.findPath(taskId, artifactId);
    }

    public DocumentArtifactResponse reExportPdf(String taskId, ReportExportOptions options) {
        find(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        GeneratedDocument document = generatedDocumentStorage.read(taskId);
        return runner.reExport(taskId, document, options);
    }
}
