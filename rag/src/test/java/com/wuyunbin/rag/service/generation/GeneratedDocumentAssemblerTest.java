package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.service.generation.model.Evidence;
import com.wuyunbin.rag.service.generation.model.GeneratedDocument;
import com.wuyunbin.rag.service.generation.model.GeneratedSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GeneratedDocumentAssembler 单测：章节保序、参考资料按 documentId 去重并保留首次出现顺序。
 */
class GeneratedDocumentAssemblerTest {

    private final GeneratedDocumentAssembler assembler = new GeneratedDocumentAssembler();

    private Evidence evidence(String documentId, String chunkId) {
        return new Evidence(documentId, documentId + ".md", chunkId, "章节", null, null, 0.9);
    }

    @Test
    void assemble_preservesSectionOrder() {
        GeneratedSection s1 = new GeneratedSection("第一章", "正文1", List.of());
        GeneratedSection s2 = new GeneratedSection("第二章", "正文2", List.of());
        GeneratedSection s3 = new GeneratedSection("第三章", "正文3", List.of());

        GeneratedDocument doc = assembler.assemble("标题", "摘要", List.of(s1, s2, s3));

        assertEquals(List.of("第一章", "第二章", "第三章"),
                doc.sections().stream().map(GeneratedSection::title).toList());
        assertEquals("标题", doc.title());
        assertEquals("摘要", doc.summary());
    }

    @Test
    void assemble_dedupesReferencesByDocument_keepingFirstOrder() {
        GeneratedSection s1 = new GeneratedSection("第一章", "正文1",
                List.of(evidence("d1", "c1"), evidence("d2", "c1")));
        GeneratedSection s2 = new GeneratedSection("第二章", "正文2",
                List.of(evidence("d1", "c1") /* 同文档同切片重复 */,
                        evidence("d1", "c2") /* 同文档不同切片，参考资料中也应折叠 */));

        GeneratedDocument doc = assembler.assemble("标题", "摘要", List.of(s1, s2));

        // 参考资料每个文档只保留一条，取首次出现：d1|c1, d2|c1
        assertEquals(2, doc.references().size());
        assertEquals(List.of("d1|c1", "d2|c1"),
                doc.references().stream()
                        .map(e -> e.documentId() + "|" + e.chunkId())
                        .toList());
    }

    @Test
    void assemble_skipsNullAndIncompleteEvidence() {
        GeneratedSection section = new GeneratedSection("第一章", "正文1",
                java.util.Arrays.asList(
                        evidence("d1", "c1"),
                        null,
                        new Evidence(null, "x.md", "c2", null, null, null, 0.8),
                        new Evidence("d3", null, "c3", null, null, null, 0.8)));

        GeneratedDocument doc = assembler.assemble("标题", "摘要", List.of(section));

        // 仅跳过 null 元素与 documentId/chunkId 缺失的证据；d3|c3 虽然 fileName 为 null 仍保留
        assertEquals(2, doc.references().size());
        assertEquals(List.of("c1", "c3"),
                doc.references().stream().map(Evidence::chunkId).toList());
    }

    @Test
    void assemble_nullEvidencesList_sectionKept() {
        GeneratedSection section = new GeneratedSection("第一章", "正文1", null);
        GeneratedDocument doc = assembler.assemble("标题", "摘要", List.of(section));

        assertEquals(1, doc.sections().size());
        assertTrue(doc.references().isEmpty());
    }

    @Test
    void assemble_nullSections_returnsEmpty() {
        GeneratedDocument doc = assembler.assemble("标题", "摘要", null);
        assertTrue(doc.sections().isEmpty());
        assertTrue(doc.references().isEmpty());
    }
}
