package com.wuyunbin.rag.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文档处理任务实体（可变）。
 *
 * <p>描述"用户选择 1~N 个已上传文档 + 自然语言要求 → 生成结构化文档 → 导出 PDF"的一次任务。</p>
 *
 * <p>Phase 2 第一版任务信息保存在内存（{@code InMemoryDocumentTaskStore}），结果 PDF 落盘；
 * 不把检索切片、生成正文、Prompt 等大文本写入本对象。</p>
 */
@Data
public class DocumentTask {

    /**
     * 任务唯一标识。
     */
    private String taskId;

    /**
     * 任务类型（SUMMARY / REPORT）。
     */
    private DocumentTaskType type;

    /**
     * 用户显式指定的资料文档 ID（doc_md5），不允许为空。
     */
    private List<String> documentIds;

    /**
     * 用户的自然语言任务要求。
     */
    private String instruction;

    /**
     * 任务状态。
     */
    private DocumentTaskStatus status;

    /**
     * 生成文档的标题（由大纲生成阶段产出）。
     */
    private String title;

    /**
     * 结果文件在服务端的落盘路径。
     */
    private String resultPath;

    /**
     * 结果文件的用户可读文件名（用于 Content-Disposition）。
     */
    private String resultFileName;

    /**
     * 失败原因（仅 FAILED 时存在）。
     */
    private String errorMessage;

    /**
     * 机器可读失败分类，供重试和排障使用。
     */
    private String errorCode;

    /**
     * 当前执行进度（0~100）。
     */
    private int progress;

    /**
     * 当前生命周期步骤，如 VALIDATING / GENERATING / EXPORTING。
     */
    private String currentStep;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /**
     * 创建一个新的 PENDING 任务。
     */
    public static DocumentTask create(DocumentTaskType type, List<String> documentIds, String instruction) {
        DocumentTask task = new DocumentTask();
        task.taskId = "task-" + UUID.randomUUID().toString().replace("-", "");
        task.type = type;
        task.documentIds = List.copyOf(documentIds);
        task.instruction = instruction;
        task.status = DocumentTaskStatus.PENDING;
        task.progress = 0;
        task.currentStep = "PENDING";
        task.createdAt = LocalDateTime.now();
        return task;
    }

    /**
     * 进入执行态。
     */
    public void markRunning() {
        this.status = DocumentTaskStatus.RUNNING;
        this.progress = 10;
        this.currentStep = "RUNNING";
        this.startedAt = LocalDateTime.now();
    }

    /**
     * 成功完成，记录标题与结果文件信息。
     */
    public void markCompleted(String title, String resultPath, String resultFileName) {
        this.status = DocumentTaskStatus.COMPLETED;
        this.progress = 100;
        this.currentStep = "COMPLETED";
        this.title = title;
        this.resultPath = resultPath;
        this.resultFileName = resultFileName;
        this.finishedAt = LocalDateTime.now();
    }

    /**
     * 失败终态，记录错误原因。
     */
    public void markFailed(String errorMessage) {
        this.status = DocumentTaskStatus.FAILED;
        this.currentStep = "FAILED";
        this.errorMessage = errorMessage;
        this.finishedAt = LocalDateTime.now();
    }

    public void updateProgress(int progress, String currentStep) {
        this.progress = Math.max(0, Math.min(100, progress));
        this.currentStep = currentStep;
    }
}
