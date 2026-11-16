package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.service.generation.model.GeneratedSection;
import com.wuyunbin.rag.service.generation.model.Claim;
import com.wuyunbin.rag.service.generation.model.VerifiedSummary;

import java.util.List;

/**
 * 文档整体摘要生成器：基于标题、用户要求与章节标题生成一段简短摘要。
 *
 * <p>不把全部章节正文塞入上下文，仅用章节标题控制范围，避免额外的长上下文开销。</p>
 */
public interface DocumentSummaryGenerator {

    /**
     * @param title       文档标题
     * @param instruction 用户任务要求
     * @param sections    已生成的有序章节
     * @return 摘要正文
     */
    String generate(String title, String instruction, List<GeneratedSection> sections);

    default VerifiedSummary generateSummary(String title, String instruction, List<GeneratedSection> sections) {
        String text = generate(title, instruction, sections);
        if (text == null || text.isBlank()) return new VerifiedSummary("", List.of(), List.of());
        Claim claim = Claim.unsupported("S1", text, "摘要没有关联已核验事实。");
        return new VerifiedSummary(text, List.of(claim.claimId()), List.of(claim));
    }
}
