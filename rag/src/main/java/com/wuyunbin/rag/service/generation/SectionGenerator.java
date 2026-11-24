package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.dto.DocumentTaskType;
import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.service.generation.model.GeneratedSection;
import com.wuyunbin.rag.service.generation.model.ReportSectionPlan;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 章节生成器：基于单个章节计划与该章节检索到的证据切片写出正文。
 *
 * <p>证据（Evidence）与正文同时产出：只用真实检索命中的切片作为来源，
 * 不让 LLM 自行编造引用。</p>
 */
public interface SectionGenerator {

    /**
     * 生成单个章节。
     *
     * @param type           任务类型（决定语气：总结 / 报告）
     * @param sectionPlan    章节计划（标题 + 目的）
     * @param evidenceChunks 本章节检索命中的切片（已限制在 Task.documentIds 内）
     * @return 含标题、正文、证据列表的已生成章节
     */
    GeneratedSection generate(DocumentTaskType type,
                              ReportSectionPlan sectionPlan,
                              List<RetrievedChunk> evidenceChunks);

    default GeneratedSection generate(DocumentTaskType type, ReportSectionPlan plan, List<RetrievedChunk> chunks,
                                      Set<String> allowedDocumentIds) {
        return generate(type, plan, chunks == null ? List.of() : chunks.stream()
                .filter(c -> c != null && c.documentId() != null && allowedDocumentIds.contains(c.documentId())).toList());
    }

    default GeneratedSection generateBatch(DocumentTaskType type, ReportSectionPlan plan, List<RetrievedChunk> chunks,
                                           Set<String> allowedDocumentIds, String batchId, boolean strict) {
        return generate(type, plan, chunks, allowedDocumentIds);
    }

    default GeneratedSection generateAnchoredBatch(DocumentTaskType type, ReportSectionPlan plan,
                                                  List<RetrievedChunk> chunks, Set<String> allowed, String batchId,
                                                  int budget, Consumer<List<ClaimVerifier.AnchorSpan>> onAnchors) {
        onAnchors.accept(List.of());
        return generateBatch(type, plan, chunks, allowed, batchId, true);
    }

    enum BatchFailureKind { INVALID_RESPONSE, TIMEOUT }

    class BatchFailure extends RuntimeException {
        private final BatchFailureKind failureKind;

        public BatchFailure(BatchFailureKind failureKind, Throwable cause) {
            super(failureKind.name(), cause);
            this.failureKind = failureKind;
        }

        public BatchFailureKind failureKind() {
            return failureKind;
        }
    }
}
