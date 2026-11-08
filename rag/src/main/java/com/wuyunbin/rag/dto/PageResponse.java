package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 网页抓取响应：URL、标题、落盘文件路径、抓取时间、Markdown 字数与正文。
 *
 * <p>注意：与 {@link PageResult}（通用分页响应）不同，本类专用于 POST /api/curl/page 接口。</p>
 */
@Schema(description = "网页抓取响应")
public record PageResponse(

        @Schema(description = "原始 URL")
        String url,

        @Schema(description = "页面标题")
        String title,

        @Schema(description = "落盘文件名")
        String fileName,

        @Schema(description = "落盘文件绝对路径")
        String filePath,

        @Schema(description = "抓取时间")
        LocalDateTime fetchedAt,

        @Schema(description = "Markdown 字符数")
        int length,

        @Schema(description = "Markdown 正文")
        String markdown
) {
}
