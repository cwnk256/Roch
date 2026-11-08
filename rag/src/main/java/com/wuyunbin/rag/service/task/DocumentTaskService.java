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
 * <p>Phase 2 第一版为同步执行：POST 创建后在同一请求内跑到 COMPLETED / FAILED。
 * 任务记录存内存，结果 PDF 落盘。</p>
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

    /**
     * 创建并同步执行一个文档任务。
     *
     * @throws IllegalArgumentException       参数非法（400）
     * @throws IllegalStateException          指定文档不存在或未就绪（404）
     * @throws DocumentTaskExecutionException 执行或导出失败（任务已置 FAILED，500）
     */
    public DocumentTaskResponse createAndExecute(DocumentTaskRequest request) {
        // 1. 参数校验
        validator.validateRequest(request);
        // 2. 资料范围校验：先校验再建任务，非法引用不产生残留任务
        validator.validateDocumentsExist(request.documentIds());

        // 3. 创建任务（PENDING）
        DocumentTask task = DocumentTask.create(request.type(), request.documentIds(), request.instruction());
        taskStore.save(task);
        log.info("[DocumentTask] taskId={} type={} documents={} instructionLength={} status=PENDING",
                task.getTaskId(), task.getType(), task.getDocumentIds().size(),
                task.getInstruction().length());

        runner.submit(task.getTaskId());
        return DocumentTaskResponse.from(task);
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
        return createAndExecute(new DocumentTaskRequest(old.getType(), old.getDocumentIds(), old.getInstruction()));
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

    public DocumentArtifactResponse reExportPdf(String taskId) {
        DocumentTask task = find(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        GeneratedDocument document = generatedDocumentStorage.read(taskId);
        return runner.reExport(taskId, document);
    }
}
