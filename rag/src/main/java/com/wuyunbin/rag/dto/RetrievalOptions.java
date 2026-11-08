package com.wuyunbin.rag.dto;

import com.wuyunbin.rag.retrieval.RetrievalMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

/**
 * 检索参数：topK、相似度阈值、可选的文档过滤集合、可选的检索模式。
 *
 * <p>documentIds 为 null 或空集表示全库搜索；非空则只在指定文档内搜索。</p>
 *
 * <p>mode 为 null 表示走全局默认配置（{@code rag.retrieval.mode}）；
 * documentIds 约束在任何模式（vector/keyword/hybrid）下都必须贯穿到每一路召回。</p>
 */
@Schema(description = "检索参数")
public record RetrievalOptions(

        @Schema(description = "召回切片条数", defaultValue = "8")
        int topK,

        @Schema(description = "相似度阈值（0~1）", defaultValue = "0.5")
        double threshold,

        @Schema(description = "限定搜索的文档 ID 集合（doc_md5）；空表示全库搜索")
        Set<String> documentIds,

        @Schema(description = "检索模式：vector / keyword / hybrid；null 走全局默认")
        RetrievalMode mode
) {

    /**
     * 兼容旧调用点的紧凑构造器：不指定模式（走全局默认）。
     */
    public RetrievalOptions(int topK, double threshold, Set<String> documentIds) {
        this(topK, threshold, documentIds, null);
    }

    /**
     * 是否启用文档过滤。
     */
    public boolean hasDocumentFilter() {
        return documentIds != null && !documentIds.isEmpty();
    }
}
