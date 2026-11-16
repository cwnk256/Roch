package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 检索命中的文档：对一组同文档切片聚合后的结果。
 *
 * <p>聚合算法（{@link com.wuyunbin.rag.service.DocumentAggregationService}）：
 * documentScore = min(maxChunkScore + min(hitCount - 1, 4) * 0.02, 1.0)</p>
 */
@Schema(description = "检索命中的文档")
public record RetrievedDocument(

        @Schema(description = "文档 ID（doc_md5）")
        String documentId,

        @Schema(description = "文件名")
        String fileName,

        @Schema(description = "文档级聚合得分（0~1）")
        double score,

        @Schema(description = "命中的切片数")
        int hitCount,

        @Schema(description = "命中的切片列表（按相似度降序）")
        List<RetrievedChunk> matchedChunks
) {
}
