package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 流式输出的单个块。
 *
 * @param event 事件类型：conversation（会话 ID）、rag（是否启用检索）、content（模型文本块）
 * @param data  事件数据
 */
@Schema(description = "流式输出块")
public record StreamChunk(

        @Schema(description = "事件类型", example = "content")
        String event,

        @Schema(description = "事件数据", example = "你好")
        String data
) {
}
