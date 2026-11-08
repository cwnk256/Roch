package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 多轮问答请求体。
 */
@Schema(description = "多轮问答请求")
public record MultiTurnChatRequest(

        @Schema(description = "会话 ID，为空则由服务端生成", example = "3f2a9c1e-8b44-4f0a-9d21-6c9b7a1f0e33")
        String conversationId,

        @Schema(description = "用户输入内容", requiredMode = Schema.RequiredMode.REQUIRED, example = "那它的价格是多少？")
        String message,

        @Schema(description = "是否启用知识库检索，缺省取服务端配置")
        Boolean ragEnabled,

        @Schema(description = "检索召回条数，范围 1-20", example = "4")
        Integer topK,

        @Schema(description = "相似度阈值，范围 0.0-1.0，缺省取服务端配置", example = "0.5")
        Double threshold
) {
}
