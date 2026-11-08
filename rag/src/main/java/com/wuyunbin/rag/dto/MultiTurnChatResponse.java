package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 多轮问答响应体。
 */
@Schema(description = "多轮问答响应")
public record MultiTurnChatResponse(

        @Schema(description = "本次实际使用的会话 ID")
        String conversationId,

        @Schema(description = "模型回复内容")
        String reply,

        @Schema(description = "该会话已完成的问答轮数")
        int round,

        @Schema(description = "是否使用了知识库检索结果")
        boolean ragUsed,

        @Schema(description = "命中的文档片段摘要列表")
        List<Reference> references
) {

    @Schema(description = "文档片段引用")
    public record Reference(

            @Schema(description = "文件名")
            String fileName,

            @Schema(description = "切片索引")
            int chunkIndex,

            @Schema(description = "相似度得分")
            double score
    ) {
    }
}
