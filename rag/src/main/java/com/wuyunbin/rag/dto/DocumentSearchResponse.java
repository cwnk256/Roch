package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 文档搜索响应：返回命中的文档列表（已按文档级得分降序）。
 */
@Schema(description = "文档搜索响应")
public record DocumentSearchResponse(

        @Schema(description = "原始查询")
        String query,

        @Schema(description = "命中的文档列表（按 score 降序）")
        List<RetrievedDocument> documents
) {
}
