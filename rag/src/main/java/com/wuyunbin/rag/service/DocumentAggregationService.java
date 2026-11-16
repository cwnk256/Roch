package com.wuyunbin.rag.service;

import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.dto.RetrievedDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 切片聚合服务：把 Chunk 级检索结果按 documentId 聚合成 Document 级结果。
 *
 * <p>聚合算法（可解释、可验证）：
 * <pre>
 *   documentScore = min(maxChunkScore + min(hitCount - 1, 4) * 0.02, 1.0)
 *   排序：documentScore DESC
 * </pre>
 * 这样可避免一个文档因切片数多而无限叠加分数。</p>
 *
 * <p>若实际 Milvus score 不在 0~1 范围，{@link #aggregate} 仍按公式计算，
 * 调用方需自行判断是否需要调整（当前 Spring AI Milvus 返回 0~1 相似度）。</p>
 */
@Slf4j
@Service
public class DocumentAggregationService {

    /**
     * 单文档命中切片加成上限（超过 5 个不再加分）。
     */
    private static final int HIT_COUNT_BONUS_CAP = 4;
    /**
     * 每多命中一个切片的加成。
     */
    private static final double HIT_COUNT_BONUS_PER = 0.02;
    /**
     * 文档得分上限。
     */
    private static final double SCORE_CAP = 1.0;

    /**
     * 把切片列表聚合成文档列表。
     *
     * @param chunks 已按相似度降序排好的切片（或乱序，内部会重排）
     * @return 文档列表（按 documentScore 降序）
     */
    public List<RetrievedDocument> aggregate(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        // 按 documentId 分组，保留原始顺序（LinkedHashMap）
        Map<String, List<RetrievedChunk>> grouped = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            String docId = chunk.documentId();
            if (docId == null) {
                continue;
            }
            grouped.computeIfAbsent(docId, k -> new ArrayList<>()).add(chunk);
        }

        List<RetrievedDocument> documents = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<RetrievedChunk>> entry : grouped.entrySet()) {
            List<RetrievedChunk> matched = entry.getValue();
            // 命中的切片按相似度降序
            matched.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());

            double maxScore = matched.stream()
                    .mapToDouble(RetrievedChunk::score)
                    .max()
                    .orElse(0.0);
            int hitCount = matched.size();
            double docScore = Math.min(
                    SCORE_CAP,
                    maxScore + Math.min(Math.max(hitCount - 1, 0), HIT_COUNT_BONUS_CAP) * HIT_COUNT_BONUS_PER
            );

            String fileName = matched.get(0).fileName();
            documents.add(new RetrievedDocument(entry.getKey(), fileName, docScore, hitCount, matched));
        }

        // 文档级排序：documentScore DESC
        documents.sort(Comparator.comparingDouble(RetrievedDocument::score).reversed());
        log.debug("切片聚合完成：输入 {} 条切片，输出 {} 个文档", chunks.size(), documents.size());
        return documents;
    }
}
