package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.service.generation.model.Evidence;
import com.wuyunbin.rag.service.generation.model.GeneratedDocument;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class EvidenceIntegrityValidator {
    public void validate(GeneratedDocument document, Set<String> allowedDocumentIds) {
        if (document == null) throw new IllegalArgumentException("生成结果为空");
        Set<String> ids = new HashSet<>(allowedDocumentIds == null ? Set.of() : allowedDocumentIds);
        if (document.references() != null) document.references().forEach(e -> validateEvidence(e, ids));
        if (document.sections() != null) document.sections().forEach(s -> {
            if (s.evidences() != null) s.evidences().forEach(e -> validateEvidence(e, ids));
        });
    }

    private void validateEvidence(Evidence evidence, Set<String> ids) {
        if (evidence == null || evidence.documentId() == null || !ids.contains(evidence.documentId()))
            throw new IllegalStateException("证据超出任务文档范围");
        if (evidence.chunkId() == null || evidence.chunkId().isBlank())
            throw new IllegalStateException("证据缺少 chunkId");
    }
}
