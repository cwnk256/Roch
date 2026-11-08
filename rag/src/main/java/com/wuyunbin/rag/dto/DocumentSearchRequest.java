package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

/**
 * 文档搜索请求：自然语言查询 + 可选的检索参数与文档过滤。
 */
@Schema(description = "文档搜索请求")
public record DocumentSearchRequest(

        @Schema(description = "自然语言查询", requiredMode = Schema.RequiredMode.REQUIRED)
        String query,

        @Schema(description = "召回切片条数（不传走默认）")
        Integer topK,

        @Schema(description = "相似度阈值（不传走默认）")
        Double threshold,

        @Schema(description = "限定搜索的文档 ID 集合（doc_md5）；空表示全库搜索")
        Set<String> documentIds,

        @Schema(description = "检索模式：vector / keyword / hybrid；不传走全局默认（vector）",
                example = "hybrid")
        String mode
) {

    /**
     * 兼容旧调用方：不指定检索模式（走全局默认）。
     */
    public DocumentSearchRequest(String query, Integer topK, Double threshold,
                                 java.util.Set<String> documentIds) {
        this(query, topK, threshold, documentIds, null);
    }
}
