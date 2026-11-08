package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 会话视图。
 */
@Schema(description = "会话信息")
public record ConversationView(

        @Schema(description = "会话 ID")
        String conversationId,

        @Schema(description = "会话标题")
        String title,

        @Schema(description = "创建时间（ISO 格式）")
        String createdAt,

        @Schema(description = "最后更新时间（ISO 格式）")
        String updatedAt
) {
}
