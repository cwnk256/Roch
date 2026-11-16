package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.service.generation.model.Evidence;
import com.wuyunbin.rag.service.generation.model.GeneratedDocument;
import com.wuyunbin.rag.service.generation.model.Claim;
import com.wuyunbin.rag.service.generation.model.SourceStatus;
import com.wuyunbin.rag.service.generation.model.SupportStatus;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
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
        if (!"v2".equals(document.schemaVersion())) return;
        Map<String,Evidence> snapshots = new HashMap<>();
        for (Evidence snapshot : document.evidenceSnapshots()) {
            validateEvidence(snapshot, ids);
            if (snapshot.evidenceId() == null || snapshot.evidenceId().isBlank() || snapshot.sourceStatus() == null
                    || snapshots.putIfAbsent(snapshot.evidenceId(), snapshot) != null) {
                throw new IllegalStateException("证据快照编号重复或缺少定位状态");
            }
            if (snapshot.sourceStatus() == SourceStatus.MATCHED && (snapshot.quote() == null
                    || snapshot.quote().isBlank() || snapshot.textHash() == null || snapshot.textHash().isBlank())) {
                throw new IllegalStateException("已匹配来源缺少原文快照");
            }
        }
        Set<String> claimIds = new HashSet<>();
        for (var section : document.sections()) {
            Set<String> localEvidence = new HashSet<>();
            if (section.evidences() != null) {
                for (Evidence evidence : section.evidences()) {
                    Evidence snapshot = snapshots.get(evidence.evidenceId());
                    if (snapshot == null || !snapshot.documentId().equals(evidence.documentId())
                            || !snapshot.chunkId().equals(evidence.chunkId())
                            || !java.util.Objects.equals(snapshot.quote(), evidence.quote())
                            || snapshot.sourceStatus() != evidence.sourceStatus()) {
                        throw new IllegalStateException("章节证据与归档快照不一致");
                    }
                    localEvidence.add(evidence.evidenceId());
                }
            }
            for (Claim claim : section.claims()) {
                validateClaim(claim, snapshots, claimIds);
                if (!localEvidence.containsAll(claim.evidenceIds())) throw new IllegalStateException("陈述引用了其他章节的证据");
            }
        }
        for (Claim claim : document.summaryClaims()) {
            validateClaim(claim, snapshots, claimIds);
            if (claim.supportStatus() != SupportStatus.INSUFFICIENT || !claim.evidenceIds().isEmpty()) {
                throw new IllegalStateException("摘要额外文本不能继承章节证据");
            }
        }
        if (!claimIds.containsAll(document.summaryClaimIds())) throw new IllegalStateException("摘要引用未知事实编号");
        if (document.comparison() != null) document.comparison().validate(document.sections(), snapshots, ids);
    }

    private void validateClaim(Claim claim, Map<String,Evidence> snapshots, Set<String> seen) {
        if (claim.claimId() == null || claim.claimId().isBlank() || !seen.add(claim.claimId())
                || claim.supportStatus() == null || claim.text() == null || claim.text().isBlank()
                || !snapshots.keySet().containsAll(claim.evidenceIds())) {
            throw new IllegalStateException("事实或证据关联无效");
        }
        if (claim.supportStatus() != SupportStatus.INSUFFICIENT && (claim.evidenceIds().isEmpty()
                || claim.evidenceIds().stream().anyMatch(id -> snapshots.get(id).sourceStatus() != SourceStatus.MATCHED))) {
            throw new IllegalStateException("未定位引文不能用于判定内容支持程度");
        }
    }

    private void validateEvidence(Evidence evidence, Set<String> ids) {
        if (evidence == null || evidence.documentId() == null || !ids.contains(evidence.documentId()))
            throw new IllegalStateException("证据超出任务文档范围");
        if (evidence.chunkId() == null || evidence.chunkId().isBlank())
            throw new IllegalStateException("证据缺少 chunkId");
    }
}
