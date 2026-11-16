package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 文档元数据：以 doc_md5 作为稳定 documentId，从 Milvus 切片元数据聚合而来。
 *
 * <p>Phase 1 不引入独立元数据存储，文档列表/详情通过聚合 Milvus 切片元数据得到。</p>
 */
@Schema(description = "文档元数据")
public record DocumentMetadata(

        @Schema(description = "文档唯一标识（doc_md5）")
        String documentId,

        @Schema(description = "文件名")
        String fileName,

        @Schema(description = "文件类型（TXT/DOCX/PDF/XLSX/XLS）")
        String fileType,

        @Schema(description = "文件内容 MD5")
        String md5,

        @Schema(description = "文件字节数（仅新导入文档携带，历史数据为 null）")
        Long fileSize,

        @Schema(description = "切片数量")
        Integer chunkCount,

        @Schema(description = "切片策略版本")
        String version,

        @Schema(description = "文档状态")
        String status,

        @Schema(description = "导入时间（仅新导入文档携带）")
        LocalDateTime createdAt
) {
}
