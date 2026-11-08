package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 会话历史消息视图。
 */
@Schema(description = "会话历史消息")
public record ChatMessageView(

        @Schema(description = "消息角色", example = "USER")
        String role,

        @Schema(description = "消息内容")
        String content
) {
}
