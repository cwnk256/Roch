package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.dto.DocumentTaskType;
import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.service.generation.model.GeneratedSection;
import com.wuyunbin.rag.service.generation.model.ReportSectionPlan;

import java.util.List;

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
}
