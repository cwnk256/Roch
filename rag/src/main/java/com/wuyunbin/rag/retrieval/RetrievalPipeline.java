package com.wuyunbin.rag.retrieval;

import com.wuyunbin.rag.dto.RetrievalOptions;
import com.wuyunbin.rag.dto.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一切片检索管道（Phase 4 Step 3）：向量 / 关键词 / 混合（RRF）三种模式的唯一入口。
 *
 * <p>模式解析：请求级 {@link RetrievalOptions#mode()} 优先，null 时回退全局配置
 * {@code rag.retrieval.mode}（默认 vector）。</p>
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>{@code documentIds} 在三种模式下都原样透传到每一路召回（Milvus 标量过滤 / PG IN 下推），
 *       管道层不做、也不允许旁路；</li>
 *   <li>VECTOR / KEYWORD 单路模式直接短路返回原始结果（保留原生 score，行为与历史版本完全一致），
 *       只有 HYBRID 走 {@link RrfFuser}；</li>
 *   <li>HYBRID 双路异常隔离：任一路失败降级为另一路单路结果，两路都失败才向上抛出，
 *       避免 PG/Milvus 单点故障拖垮整条检索链路。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalPipeline {

    private final ChunkRetriever vectorRetriever;
    private final KeywordRetriever keywordRetriever;
    private final RrfFuser rrfFuser;
    private final RetrievalProperties properties;

    /**
     * 按解析后的模式执行检索。
     *
     * @param query   查询文本
     * @param options 检索参数（topK、threshold、documentIds、可选 mode）
     * @return 切片列表（按该模式相关性降序），可能为空
     */
    public List<RetrievedChunk> search(String query, RetrievalOptions options) {
        RetrievalMode mode = options.mode() != null ? options.mode() : properties.defaultMode();
        return switch (mode) {
            case KEYWORD -> keywordRetriever.search(query, options);
            case HYBRID -> hybridSearch(query, options);
            case VECTOR -> vectorRetriever.search(query, options);
        };
    }

    /**
     * 双路召回 + RRF 融合；documentIds 随同一 options 透传给两路。
     */
    private List<RetrievedChunk> hybridSearch(String query, RetrievalOptions options) {
        List<RetrievedChunk> vectorHits = null;
        List<RetrievedChunk> keywordHits = null;
        RuntimeException vectorError = null;
        RuntimeException keywordError = null;

        try {
            vectorHits = vectorRetriever.search(query, options);
        } catch (RuntimeException e) {
            vectorError = e;
            log.warn("Hybrid 检索：向量路失败，降级仅用关键词路 query={}", query, e);
        }
        try {
            keywordHits = keywordRetriever.search(query, options);
        } catch (RuntimeException e) {
            keywordError = e;
            log.warn("Hybrid 检索：关键词路失败，降级仅用向量路 query={}", query, e);
        }

        if (vectorError != null && keywordError != null) {
            log.error("Hybrid 检索：向量路与关键词路均失败 query={}", query);
            throw new IllegalStateException("混合检索双路均失败: " + vectorError.getMessage(), vectorError);
        }

        // 一路失败时直接返回另一路原始结果（保留原生 score 与排序）
        if (vectorError != null) {
            return limit(keywordHits, options.topK());
        }
        if (keywordError != null) {
            return limit(vectorHits, options.topK());
        }
        if (vectorHits.isEmpty() && keywordHits.isEmpty()) {
            return List.of();
        }

        List<RrfFuser.RankedList> rankedLists = new ArrayList<>(2);
        // 向量路在前：RRF 平局时稳定优先语义结果
        rankedLists.add(new RrfFuser.RankedList(vectorHits, properties.getVectorWeight()));
        rankedLists.add(new RrfFuser.RankedList(keywordHits, properties.getKeywordWeight()));
        List<RetrievedChunk> fused = rrfFuser.fuse(rankedLists, properties.getRrfK(), options.topK());
        log.debug("Hybrid 检索完成 query={} 向量{}条 关键词{}条 融合输出{}条",
                query, vectorHits.size(), keywordHits.size(), fused.size());
        return fused;
    }

    private static List<RetrievedChunk> limit(List<RetrievedChunk> chunks, int topK) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return chunks.size() <= topK ? chunks : chunks.subList(0, topK);
    }
}
