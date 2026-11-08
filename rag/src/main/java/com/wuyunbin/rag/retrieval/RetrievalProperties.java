package com.wuyunbin.rag.retrieval;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 检索管道配置（Phase 4 Step 3）。
 *
 * <p>默认模式 vector（纯向量），保持与历史版本行为一致；hybrid 需 benchmark 证明收益后再切换默认值。</p>
 */
@Getter
@Setter
@Component
public class RetrievalProperties {

    /**
     * 全局默认检索模式：vector / keyword / hybrid，可被请求级 mode 参数覆盖。
     */
    @Value("${rag.retrieval.mode:vector}")
    private String mode;

    /**
     * RRF 常数 k：越大则排名间差异越平滑，论文推荐 60。
     */
    @Value("${rag.retrieval.rrf.k:60}")
    private int rrfK;

    /**
     * RRF 向量路权重。
     */
    @Value("${rag.retrieval.rrf.vector-weight:1.0}")
    private double vectorWeight;

    /**
     * RRF 关键词路权重。
     */
    @Value("${rag.retrieval.rrf.keyword-weight:1.0}")
    private double keywordWeight;

    /**
     * 解析后的默认检索模式（非法配置 fail-fast）。
     */
    public RetrievalMode defaultMode() {
        RetrievalMode parsed = RetrievalMode.fromString(mode);
        if (parsed == null) {
            return RetrievalMode.VECTOR;
        }
        return parsed;
    }
}
