package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.dto.DocumentTaskType;
import com.wuyunbin.rag.dto.RetrievedDocument;
import com.wuyunbin.rag.service.generation.model.ReportPlan;

import java.util.List;

/**
 * 大纲生成器：根据用户要求与指定文档元信息，产出 {@link ReportPlan}。
 *
 * <p>这里只向 LLM 提供文档元信息与少量内容预览，不把全部 Chunk 一次性塞入；
 * 章节正文所需证据由后续 SectionGenerator 的章节级检索提供。</p>
 */
public interface OutlineGenerator {

    /**
     * 生成文档大纲。
     *
     * @param instruction 用户任务要求
     * @param type        任务类型（SUMMARY / REPORT）
     * @param documents   与任务相关的文档级检索结果（携带元信息与少量命中切片）
     * @return 结构化大纲
     */
    ReportPlan generate(String instruction, DocumentTaskType type, List<RetrievedDocument> documents);
}
