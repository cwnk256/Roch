package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 文档导入响应体。
 */
@Schema(description = "文档导入响应")
public record DocumentResponse(

        @Schema(description = "本次导入的切片数量（重复文件跳过导入时为 0）")
        int chunkCount,

        @Schema(description = "文件内容的 MD5 标识")
        String md5,

        @Schema(description = "是否为重复文件（true 表示已存在，未重复导入）")
        boolean duplicated,

        @Schema(description = "处理结果说明")
        String message
) {
}
