package com.wuyunbin.rag.retrieval;

import com.wuyunbin.rag.dto.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion（RRF）融合器。
 *
 * <p>标准 RRF 公式（Cormack et al., 2009）：
 * <pre>
 *   rrfScore(d) = Σ_i  weight_i / (k + rank_i(d))
 * </pre>
 * rank 从 1 开始；k（典型 60）抑制头部排名差异；weight 为各路权重。
 * 只依赖"排名"而非原始分数，因此天然规避向量 score 与 ts_rank 量纲不一致的问题。</p>
 *
 * <p>输出口径：融合分按本次结果最大值线性归一化到 [0,1]（top1=1.0），
 * 以兼容 {@code DocumentAggregationService} 的 0~1 分聚合公式；排序与归一化前完全一致。
 * 同一切片（chunkId 相同）在多路命中时合并为一条，并补齐各路独有的元数据
 * （例如关键词路携带的 pageStart/pageEnd）。</p>
 */
@Component
public class RrfFuser {

    /**
     * 一路带权重的排名结果（列表必须已按该路相关性降序）。
     */
    public record RankedList(List<RetrievedChunk> chunks, double weight) {
    }

    /**
     * 多路 RRF 融合。
     *
     * @param rankedLists 按优先级排列的召回结果（建议向量在前，作为同分平局时的稳定顺序）
     * @param k           RRF 常数，典型 60
     * @param topK        最终返回条数
     * @return 融合去重后的切片，按 RRF 分降序，不超过 topK 条
     */
    public List<RetrievedChunk> fuse(List<RankedList> rankedLists, int k, int topK) {
        if (rankedLists == null || rankedLists.isEmpty()) {
            return List.of();
        }
        int safeK = Math.max(1, k);
        int limit = Math.max(1, topK);

        // 插入顺序 = 各路列表展开顺序；同分平局时保持稳定（向量路优先）
        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        int nullKeySeq = 0;

        for (RankedList rankedList : rankedLists) {
            if (rankedList == null || rankedList.chunks() == null || rankedList.chunks().isEmpty()) {
                continue;
            }
            double weight = rankedList.weight();
            List<RetrievedChunk> chunks = rankedList.chunks();
            for (int rank = 1; rank <= chunks.size(); rank++) {
                RetrievedChunk chunk = chunks.get(rank - 1);
                String key = chunk.chunkId() != null ? chunk.chunkId() : ("__null_chunk__" + (nullKeySeq++));
                double contribution = weight / (safeK + rank);
                rrfScores.merge(key, contribution, Double::sum);
                // 首现切片为基底，后现切片只补齐 null 字段（如 PG 路的页码）
                merged.merge(key, chunk, RrfFuser::mergeChunk);
            }
        }

        if (merged.isEmpty()) {
            return List.of();
        }

        double maxRrf = rrfScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        List<Map.Entry<String, Double>> ordered = new ArrayList<>(rrfScores.entrySet());
        // LinkedHashMap 的 entrySet 已保持插入顺序，稳定排序使平局时首现者（向量路排名靠前者）在前
        ordered.sort(Comparator.comparingDouble(Map.Entry<String, Double>::getValue).reversed());

        List<RetrievedChunk> result = new ArrayList<>(Math.min(limit, ordered.size()));
        for (Map.Entry<String, Double> entry : ordered) {
            if (result.size() >= limit) {
                break;
            }
            RetrievedChunk base = merged.get(entry.getKey());
            double normalized = maxRrf > 0 ? entry.getValue() / maxRrf : 0.0;
            result.add(withScore(base, normalized));
        }
        return result;
    }

    /**
     * 以先出现的切片为基底，用后出现切片的非 null 字段补齐；content/fileName 等同源字段必然一致。
     */
    private static RetrievedChunk mergeChunk(RetrievedChunk base, RetrievedChunk other) {
        return new RetrievedChunk(
                base.chunkId(),
                base.documentId() != null ? base.documentId() : other.documentId(),
                base.fileName() != null ? base.fileName() : other.fileName(),
                base.content() != null && !base.content().isEmpty() ? base.content() : other.content(),
                base.section() != null ? base.section() : other.section(),
                base.pageStart() != null ? base.pageStart() : other.pageStart(),
                base.pageEnd() != null ? base.pageEnd() : other.pageEnd(),
                base.chunkIndex() != null ? base.chunkIndex() : other.chunkIndex(),
                base.score()
        );
    }

    private static RetrievedChunk withScore(RetrievedChunk c, double score) {
        return new RetrievedChunk(
                c.chunkId(), c.documentId(), c.fileName(), c.content(),
                c.section(), c.pageStart(), c.pageEnd(), c.chunkIndex(), score
        );
    }
}
