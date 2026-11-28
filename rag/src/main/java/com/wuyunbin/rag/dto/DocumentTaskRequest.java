package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "文档任务创建请求")
public record DocumentTaskRequest(
        @Schema(description = "任务类型", requiredMode = Schema.RequiredMode.REQUIRED) DocumentTaskType type,
        @Schema(description = "指定资料的文档 ID", requiredMode = Schema.RequiredMode.REQUIRED) List<String> documentIds,
        @Schema(description = "总生成要求", requiredMode = Schema.RequiredMode.REQUIRED) String instruction,
        String templateId,
        String templateVersion,
        @Schema(description = "依报告显示顺序选择章节，省略时使用全部模板章节") List<SectionSelection> sections,
        ReportExportOptions exportOptions,
        @Schema(description = "文档对比的取证维度") List<String> comparisonDimensions) {
    public record SectionSelection(String sectionId, String instruction) {}

    public DocumentTaskRequest(DocumentTaskType type, List<String> documentIds, String instruction) {
        this(type, documentIds, instruction, null, null, null, null, null);
    }
}
