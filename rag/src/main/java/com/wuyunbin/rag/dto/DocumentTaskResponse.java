package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 文档任务响应：任务状态 + 结果文件下载信息。
 *
 * <p>创建、查询、重试与进度推送共用此快照。</p>
 */
@Schema(description = "文档任务响应")
public record DocumentTaskResponse(

        @Schema(description = "任务 ID")
        String taskId,

        @Schema(description = "任务类型")
        DocumentTaskType type,

        @Schema(description = "任务状态")
        DocumentTaskStatus status,

        @Schema(description = "生成文档标题")
        String title,

        @Schema(description = "结果文件可读文件名（如 设备维护总结.pdf）")
        String resultFileName,

        @Schema(description = "结果文件下载地址（COMPLETED 时存在）")
        String downloadUrl,

        @Schema(description = "失败原因（FAILED 时存在）")
        String errorMessage,

        @Schema(description = "已完成进度（0–100），失败时保留最后进度")
        int progress,

        @Schema(description = "当前处理步骤")
        String currentStep,

        @Schema(description = "机器可读的失败原因")
        String errorCode,

        ReportConfiguration configuration
) {

    /**
     * 由任务实体构造响应；COMPLETED 时附带下载地址，其余状态 downloadUrl 为 null。
     */
    public static DocumentTaskResponse from(DocumentTask task) {
        String downloadUrl = task.getStatus() == DocumentTaskStatus.COMPLETED
                ? "/api/document-tasks/" + task.getTaskId() + "/file"
                : null;
        return new DocumentTaskResponse(
                task.getTaskId(),
                task.getType(),
                task.getStatus(),
                task.getTitle(),
                task.getResultFileName(),
                downloadUrl,
                task.getErrorMessage(), task.getProgress(), task.getCurrentStep(), task.getErrorCode(), task.getConfiguration()
        );
    }
}
