package com.wuyunbin.rag.service.comparison;

import com.wuyunbin.rag.service.generation.model.*;
import java.util.*;

/** Ordered matrix saved with the report. Facts refer to the same claims used by the reader and exporter. */
public record ComparisonResult(List<Source> documents, List<Row> rows, List<Conclusion> conclusions, Metrics metrics) {
    public record Source(String documentId, String fileName) {}
    public record Row(String dimension, List<Cell> cells) {}
    public record Cell(String documentId, Status status, String reason, List<Fact> facts) {}
    public enum Status { FOUND, NOT_FOUND, RETRIEVAL_FAILED, EXTRACTION_FAILED, UNVERIFIED }
    public record Fact(String claimId, String object, String period, String condition, String metric,
                       String rawValue, String unit, String normalizedValue, String normalizedUnit,
                       boolean verified, boolean fixed, boolean textual, String modality, String quote) {}
    public record Conclusion(Kind kind, String dimension, String text, List<String> claimIds) {}
    public enum Kind { COMMON, DIFFERENT, KEY_DATA, POSSIBLE_CONFLICT, INCOMPARABLE }
    public record Metrics(int retrievalCalls, int modelCalls, long elapsedMillis) {}

    public void validate(List<GeneratedSection> sections, Map<String, Evidence> snapshots, Set<String> allowed) {
        List<String> columns = documents.stream().map(Source::documentId).toList();
        if (columns.size() < 2 || columns.size() != allowed.size() || !allowed.equals(new HashSet<>(columns))) {
            throw new IllegalStateException("对比表缺少指定文档或包含重复文档");
        }
        Map<String, Claim> claims = new HashMap<>();
        sections.forEach(section -> section.claims().forEach(claim -> claims.put(claim.claimId(), claim)));
        Map<String, String> factDocuments = new HashMap<>();
        Map<String, Set<String>> eligible = new HashMap<>();
        for (Row row : rows) {
            Set<String> supported = new HashSet<>();
            if (eligible.putIfAbsent(row.dimension(), supported) != null ||
                    !columns.equals(row.cells().stream().map(Cell::documentId).toList())) {
                throw new IllegalStateException("对比维度重复或单元格与文档列不符");
            }
            for (Cell cell : row.cells()) {
                boolean found = false;
                for (Fact fact : cell.facts()) {
                    Claim claim = claims.get(fact.claimId());
                    if (claim == null || factDocuments.putIfAbsent(fact.claimId(), cell.documentId()) != null ||
                            claim.evidenceIds().stream().map(snapshots::get).anyMatch(
                                    evidence -> evidence == null || !cell.documentId().equals(evidence.documentId()))) {
                        throw new IllegalStateException("对比单元格的事实编号或证据归属无效");
                    }
                    if (fact.verified()) {
                        if (claim.evidenceIds().isEmpty() || claim.supportStatus() != SupportStatus.SUPPORTED) {
                            throw new IllegalStateException("比较字段所依赖的事实未获支持");
                        }
                        found = true;
                        supported.add(fact.claimId());
                    }
                }
                if (found != (cell.status() == Status.FOUND)
                        || (!cell.facts().isEmpty() && !found && cell.status() != Status.UNVERIFIED)) {
                    throw new IllegalStateException("对比单元格状态与事实不符");
                }
            }
        }
        for (Conclusion conclusion : conclusions) {
            var ids = conclusion.claimIds();
            boolean single = conclusion.kind() == Kind.KEY_DATA;
            if (ids.isEmpty() || !eligible.getOrDefault(conclusion.dimension(), Set.of()).containsAll(ids)
                    || (single && ids.size() != 1)
                    || (!single && ids.stream().map(factDocuments::get).distinct().count() < 2)) {
                throw new IllegalStateException("对比结论缺少同一维度参与文档的核验事实");
            }
        }
    }
}
