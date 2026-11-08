package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.service.generation.model.Evidence;
import com.wuyunbin.rag.service.generation.model.GeneratedDocument;
import com.wuyunbin.rag.service.generation.model.GeneratedSection;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化文档组装器：保持大纲章节顺序，汇总并按 documentId 去重证据，
 * 形成最终 {@link GeneratedDocument}。
 *
 * <p>最终参考资料按文档去重（同一文档的多个切片只保留首次出现的一条，
 * 即参考资料列表中每个文件只出现一次）；章节级 {@code evidences} 仍保留切片粒度。</p>
 *
 * <p>不调用 LLM、不接触 Milvus / PDF，只负责纯数据组装，便于独立单测。</p>
 */
@Component
public class GeneratedDocumentAssembler {

    /**
     * @param title    文档标题
     * @param summary  文档摘要
     * @param sections 已按大纲顺序生成的章节
     */
    public GeneratedDocument assemble(String title, String summary, List<GeneratedSection> sections) {
        List<GeneratedSection> safeSections = sections == null ? List.of() : List.copyOf(sections);

        // 参考资料按 documentId 去重（同一文档的不同切片只保留首次出现的一条），
        // 保留首次出现顺序（即章节顺序 + 章节内顺序）
        Map<String, Evidence> deduped = new LinkedHashMap<>();
        for (GeneratedSection section : safeSections) {
            if (section.evidences() == null) {
                continue;
            }
            for (Evidence evidence : section.evidences()) {
                if (evidence == null || evidence.documentId() == null || evidence.chunkId() == null) {
                    continue;
                }
                deduped.putIfAbsent(evidence.documentId(), evidence);
            }
        }
        return new GeneratedDocument(title, summary, safeSections, List.copyOf(deduped.values()));
    }
}
