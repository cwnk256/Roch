package com.wuyunbin.rag.service.comparison;

import com.wuyunbin.rag.dto.*;
import com.wuyunbin.rag.retrieval.RetrievalPipeline;
import com.wuyunbin.rag.service.DocumentService;
import com.wuyunbin.rag.service.generation.ClaimVerifier;
import com.wuyunbin.rag.service.generation.GeneratedDocumentAssembler;
import com.wuyunbin.rag.service.generation.LlmSectionGenerator;
import com.wuyunbin.rag.service.generation.model.*;
import com.wuyunbin.rag.service.task.ComparisonLimits;
import com.wuyunbin.rag.service.task.DocumentTaskProperties;
import com.wuyunbin.rag.service.task.TaskEvidenceLog;
import com.wuyunbin.rag.service.task.TaskEvidenceRetrieval;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

import static com.wuyunbin.rag.service.comparison.ComparisonResult.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ComparisonGenerator {
    private final RetrievalPipeline retrieval;
    private final ComparisonExtractor extractor;
    private final ClaimVerifier verifier;
    private final DocumentService documentService;
    private final GeneratedDocumentAssembler assembler;
    private final DocumentTaskProperties properties;
    private final TaskEvidenceRetrieval taskRetrieval;
    private final TaskEvidenceLog evidenceLog;

    private static class Run {
        final List<Claim> claims = new ArrayList<>();
        final Map<String, Evidence> snapshots = new LinkedHashMap<>();
        int retrievalCalls;
        int modelCalls;
    }

    public GeneratedDocument generate(DocumentTask task, int topK, double threshold) {
        long start = System.nanoTime();
        var config = Objects.requireNonNull(task.getConfiguration(), "比较任务缺少创建时的模板配置");
        ComparisonLimits.validate(task.getDocumentIds(), config.comparisonDimensions());
        List<Source> sources = task.getDocumentIds().stream().map(id -> {
            var metadata = documentService.getDocument(id);
            return new Source(id, metadata == null || metadata.fileName() == null ? id : metadata.fileName());
        }).toList();

        Run run = new Run();
        List<Row> rows = new ArrayList<>();
        for (String dimension : config.comparisonDimensions()) {
            List<Cell> cells = new ArrayList<>();
            for (Source source : sources) {
                String prefix = "M-" + (rows.size() + 1) + "-" + (cells.size() + 1) + "-";
                cells.add(collect(task.getTaskId(), rows.size() + 1, cells.size() + 1,
                        source, dimension, config, prefix, topK, threshold, run));
            }
            rows.add(new Row(dimension, List.copyOf(cells)));
        }
        List<Conclusion> conclusions = ComparisonRules.conclusions(sources, rows, strict(config),
                config.retrieval() != null && config.retrieval().anchored());
        List<GeneratedSection> sections = new ArrayList<>();
        List<Conclusion> published = new ArrayList<>();
        for (var section : config.sections()) {
            if ("matrix".equals(section.sectionId())) {
                sections.add(new GeneratedSection(section.title(), matrixText(sources, rows),
                        List.copyOf(run.snapshots.values()), List.copyOf(run.claims)));
            } else {
                List<Conclusion> selected = conclusions.stream()
                        .filter(c -> section.sectionId().equals(sectionId(c.kind()))).toList();
                published.addAll(selected);
                sections.add(new GeneratedSection(section.title(), selected.isEmpty() ? "本次没有符合该项的可核验结论。"
                        : selected.stream().map(Conclusion::text).collect(Collectors.joining("\n")), List.of(), List.of()));
            }
        }
        var summary = summary(published);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        var comparison = new ComparisonResult(sources, List.copyOf(rows), conclusions,
                new Metrics(run.retrievalCalls, run.modelCalls, elapsed));
        log.info("比较完成 taskId={} documents={} dimensions={} retrievalCalls={} modelCalls={} elapsedMs={}",
                task.getTaskId(), sources.size(), rows.size(), run.retrievalCalls, run.modelCalls, elapsed);
        return assembler.assemble(config.title(), summary, sections).withComparison(comparison).withConfiguration(config);
    }

    private Cell collect(String taskId, int section, int batch, Source source, String dimension,
                         ReportConfiguration config, String prefix, int topK, double threshold, Run run) {
        Set<String> scope = Set.of(source.documentId());
        ReportRetrievalSettings settings = config.retrieval();
        boolean strict = strict(config);
        boolean anchored = settings != null && settings.anchored();
        var cellScope = anchored ? ComparisonCellScope.from(dimension, config) : null;
        String requestedScope = anchored ? cellScope.prompt() : comparisonScope(dimension, config);
        List<RetrievedChunk> selected;
        int budget = settings == null ? Math.min(8000, properties.getMaxContextChars()) : settings.comparisonMaxChars();
        run.retrievalCalls++;
        long started = System.nanoTime();
        try {
            boolean hybrid = settings != null && settings.hybrid();
            int savedTopK = settings == null ? topK : settings.topK();
            int selectedLimit = Math.min(8, Math.max(1, savedTopK));
            double savedThreshold = settings == null ? threshold : settings.threshold();
            var hits = hybrid
                    ? taskRetrieval.hybrid(settings.splitQuestions() ? requestedScope : dimension, scope, settings)
                    : retrieval.search(dimension, new RetrievalOptions(selectedLimit, savedThreshold, scope,
                            settings == null ? null : settings.baselineMode()));
            selected = LlmSectionGenerator.selectWithinBudget(hits.stream().limit(selectedLimit).toList(), scope, budget);
            evidenceLog.event(taskId, section, batch, "context", selected.isEmpty() ? "empty" : "selected",
                    hits.size(), selected, started);
        } catch (RuntimeException ex) {
            log.warn("对比检索失败 document={} dimension={}", source.documentId(), dimension, ex);
            evidenceLog.event(taskId, section, batch, "retrieval", "failed", 0, List.of(), started);
            return new Cell(source.documentId(), Status.RETRIEVAL_FAILED, "检索失败", List.of(),
                    anchored ? ReasonCode.RETRIEVAL_FAILED : null);
        }
        if (anchored) return collectAnchored(taskId, section, batch, source, cellScope, prefix, selected, budget, started, run);
        if (selected.isEmpty()) return missing(source);

        List<ComparisonCandidate> candidates;
        run.modelCalls++;
        try {
            candidates = strict ? extractWithRetry(dimension, config, selected, run)
                    : extractor.extract(dimension, config, selected);
        } catch (RuntimeException ex) {
            log.warn("对比事实提取失败 document={} dimension={}", source.documentId(), dimension, ex);
            evidenceLog.event(taskId, section, batch, "generation", "extraction-failed", selected.size(), List.of(), started);
            return new Cell(source.documentId(), Status.EXTRACTION_FAILED, "模型未能完成事实提取", List.of());
        }
        if (candidates.isEmpty()) {
            evidenceLog.event(taskId, section, batch, "generation", "not-found", selected.size(), List.of(), started);
            return missing(source);
        }
        List<Fact> facts = new ArrayList<>();
        for (ComparisonCandidate selectedCandidate : candidates) {
            var candidate = selectedCandidate.source() <= 0 ? selectedCandidate
                    : ExplicitComparisonFacts.completeFields(dimension, selectedCandidate);
            String claimId = prefix + (facts.size() + 1);
            var citations = List.of(new ClaimVerifier.Citation(candidate.source(), candidate.quote()));
            var verified = strict
                    ? verifier.verifyStrict(claimId, candidate.statement(), citations, selected, scope, requestedScope)
                    : verifier.verify(claimId, candidate.statement(), citations, selected, scope);
            if (verified.evidences().stream().anyMatch(e -> e.sourceStatus() == SourceStatus.MATCHED)) {
                // The shared verifier invokes one support judgment for a single matched citation.
                run.modelCalls++;
            }
            boolean supported = verified.claim().supportStatus() == SupportStatus.SUPPORTED;
            var originalQuote = verified.evidences().stream().filter(e -> e.sourceStatus() == SourceStatus.MATCHED)
                    .map(Evidence::quote).findFirst().orElse(candidate.quote());
            Fact fact = ComparisonRules.fact(claimId, candidate, supported, originalQuote, strict);
            facts.add(fact);
            run.claims.add(verified.claim());
            verified.evidences().forEach(e -> run.snapshots.putIfAbsent(e.evidenceId(), e));
        }
        boolean found = facts.stream().anyMatch(Fact::verified);
        evidenceLog.event(taskId, section, batch, "generation", found ? "completed" : "unverified",
                selected.size(), List.of(), started);
        return new Cell(source.documentId(), found ? Status.FOUND : Status.UNVERIFIED,
                facts.stream().allMatch(Fact::verified) ? "" : "部分或全部原文、事实或比较字段未通过核验",
                List.copyOf(facts));
    }

    private Cell collectAnchored(String taskId, int section, int batch, Source source, ComparisonCellScope scope,
                                 String prefix, List<RetrievedChunk> selected, int budget, long started, Run run) {
        if (selected.isEmpty()) {
            evidenceLog.anchors(taskId, section, batch, List.of(), started);
            return anchoredCell(source, Status.NOT_FOUND, ReasonCode.NO_ORIGINAL_EVIDENCE, "本次未找到依据");
        }
        Map<String, AnchoredComparisonFacts.Selection> candidates = new LinkedHashMap<>();
        ReasonCode incomplete = null;
        try {
            var evidence = verifier.evidenceOptions(selected, scope.dimension(), budget);
            evidenceLog.anchors(taskId, section, batch, evidence.spans(selected), started);
            if (evidence.citations().isEmpty())
                return anchoredCell(source, Status.NOT_FOUND, ReasonCode.NO_ORIGINAL_EVIDENCE, "本次未找到可回查的原文");
            var inScope = evidence.citations().entrySet().stream()
                    .filter(entry -> scope.permits(entry.getValue().quote())).toList();
            if (inScope.isEmpty())
                return anchoredCell(source, Status.NOT_FOUND, ReasonCode.OUTSIDE_SCOPE, "原文中未找到符合范围的事实");
            var relevant = inScope.stream().filter(entry -> AnchoredComparisonFacts.possiblyRelevant(
                    scope.dimension(), entry.getValue().quote())).toList();
            if (relevant.isEmpty())
                return anchoredCell(source, Status.NOT_FOUND, ReasonCode.NO_MATCHING_FACT, "本次未找到符合当前维度的依据");
            List<Map.Entry<String, ClaimVerifier.Citation>> unresolved = new ArrayList<>();
            for (var entry : relevant) {
                var literal = AnchoredComparisonFacts.literal(scope, entry.getValue());
                if (literal == null) unresolved.add(entry);
                else candidates.putIfAbsent(factKey(literal), literal);
            }
            if (!unresolved.isEmpty() && candidates.size() < ComparisonExtractor.MAX_FACTS) {
                var narrowed = narrowEvidence(unresolved);
                try {
                    var draft = extractAnchoredWithRetry(scope, narrowed, run);
                    var extracted = resolveDraft(scope, narrowed, draft);
                    if (extracted.size() < draft.numericFacts().size() + draft.obligationFacts().size())
                        incomplete = ReasonCode.INVALID_FIELD_OR_ANCHOR;
                    for (var fact : extracted) candidates.putIfAbsent(factKey(fact), fact);
                } catch (RuntimeException ex) {
                    log.warn("对比补充事实提取失败 document={} dimension={}", source.documentId(), scope.dimension(), ex);
                    incomplete = ReasonCode.EXTRACTION_FAILED;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("对比事实提取失败 document={} dimension={}", source.documentId(), scope.dimension(), ex);
            evidenceLog.event(taskId, section, batch, "generation", "extraction-failed", selected.size(), List.of(), started);
            return anchoredCell(source, Status.EXTRACTION_FAILED, ReasonCode.EXTRACTION_FAILED, "未能完成原文事实提取");
        }
        if (candidates.isEmpty()) {
            if (incomplete == ReasonCode.EXTRACTION_FAILED)
                return anchoredCell(source, Status.EXTRACTION_FAILED, incomplete, "未能完成原文事实提取");
            if (incomplete == ReasonCode.INVALID_FIELD_OR_ANCHOR)
                return anchoredCell(source, Status.UNVERIFIED, incomplete, "提取结果在当前维度或原文中无法核实");
            return anchoredCell(source, Status.NOT_FOUND, ReasonCode.NO_MATCHING_FACT, "本次未找到符合当前维度的依据");
        }

        List<Fact> facts = new ArrayList<>();
        for (var selection : candidates.values().stream().limit(ComparisonExtractor.MAX_FACTS).toList()) {
            var citation = selection.citation();
            var candidate = selection.candidate();
            String claimId = prefix + (facts.size() + 1);
            String requestedScope = scope.verificationScope(selection);
            var verified = verifier.verifyAnchoredComparison(claimId, candidate.statement(), citation, selected,
                    Set.of(source.documentId()), requestedScope, selection.literalFieldsProven());
            if (!requestedScope.isBlank() && verified.evidences().stream().anyMatch(e -> e.sourceStatus() == SourceStatus.MATCHED))
                run.modelCalls++;
            var originalQuote = verified.evidences().stream().filter(e -> e.sourceStatus() == SourceStatus.MATCHED)
                    .map(Evidence::quote).findFirst().orElse(candidate.quote());
            facts.add(ComparisonRules.fact(claimId, candidate, verified.claim().supportStatus() == SupportStatus.SUPPORTED,
                    originalQuote, true, true, selection.semanticText()));
            run.claims.add(verified.claim());
            verified.evidences().forEach(e -> run.snapshots.putIfAbsent(e.evidenceId(), e));
        }
        boolean found = facts.stream().anyMatch(Fact::verified);
        boolean allVerified = facts.stream().allMatch(Fact::verified);
        evidenceLog.event(taskId, section, batch, "generation", found ? "completed" : "unverified",
                selected.size(), List.of(), started);
        String reason = allVerified ? "" : "部分或全部原文、事实或比较字段未通过核验";
        if (incomplete == ReasonCode.EXTRACTION_FAILED) reason = "部分原文的事实提取未完成";
        if (incomplete == ReasonCode.INVALID_FIELD_OR_ANCHOR) reason = "部分提取结果在当前维度或原文中无法核实";
        return new Cell(source.documentId(), found ? Status.FOUND : Status.UNVERIFIED,
                reason, List.copyOf(facts), incomplete != null ? incomplete : allVerified ? null : ReasonCode.INSUFFICIENT_SUPPORT);
    }

    private static String factKey(AnchoredComparisonFacts.Selection selection) {
        var citation = selection.citation();
        return Objects.toString(citation.cellLocation(), "") + "\n" + citation.quote() + "\n"
                + selection.candidate().statement();
    }

    private ComparisonExtractor.AtomicExtraction extractAnchoredWithRetry(ComparisonCellScope scope,
                                                                            ClaimVerifier.EvidenceOptions evidence, Run run) {
        run.modelCalls++;
        try {
            return extractor.extractAnchored(scope, evidence);
        } catch (RuntimeException ex) {
            if (!retryable(ex)) throw ex;
            run.modelCalls++;
            return extractor.extractAnchored(scope, evidence);
        }
    }

    private static ClaimVerifier.EvidenceOptions narrowEvidence(List<Map.Entry<String, ClaimVerifier.Citation>> relevant) {
        Map<String, ClaimVerifier.Citation> byId = new LinkedHashMap<>();
        StringBuilder prompt = new StringBuilder();
        for (var entry : relevant) {
            var citation = entry.getValue();
            byId.put(entry.getKey(), citation);
            prompt.append('[').append(entry.getKey()).append("] ");
            if (citation.cellLocation() != null) prompt.append(citation.cellLocation()).append(' ');
            prompt.append(Objects.requireNonNullElse(citation.nativeContext(), citation.quote())).append('\n');
        }
        return new ClaimVerifier.EvidenceOptions(prompt.toString(), Collections.unmodifiableMap(byId));
    }

    private static List<AnchoredComparisonFacts.Selection> resolveDraft(ComparisonCellScope scope,
                                                                         ClaimVerifier.EvidenceOptions evidence,
                                                                         ComparisonExtractor.AtomicExtraction draft) {
        List<AnchoredComparisonFacts.Selection> selected = new ArrayList<>();
        for (var fact : draft.numericFacts()) {
            var candidate = AnchoredComparisonFacts.draft(scope, evidence.citations().get(fact.evidenceId()),
                    AnchoredComparisonFacts.Type.NUMERIC, fact.object(), fact.period(), fact.condition(),
                    fact.metric(), fact.rawValue(), fact.unit());
            if (candidate != null) selected.add(candidate);
        }
        for (var fact : draft.obligationFacts()) {
            var candidate = AnchoredComparisonFacts.draft(scope, evidence.citations().get(fact.evidenceId()),
                    AnchoredComparisonFacts.Type.OBLIGATION, fact.object(), fact.period(), fact.condition(),
                    fact.metric(), fact.obligation(), "");
            if (candidate != null) selected.add(candidate);
        }
        return selected;
    }

    private static Cell anchoredCell(Source source, Status status, ReasonCode reasonCode, String reason) {
        return new Cell(source.documentId(), status, reason, List.of(), reasonCode);
    }

    private List<ComparisonCandidate> extractWithRetry(String dimension, ReportConfiguration config,
                                                       List<RetrievedChunk> selected, Run run) {
        try {
            return extractor.extractStrict(dimension, config, selected);
        } catch (RuntimeException ex) {
            if (!retryable(ex)) throw ex;
            run.modelCalls++;
            return extractor.extractStrict(dimension, config, selected);
        }
    }

    private static boolean retryable(Throwable error) {
        for (Throwable cause = error; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof ComparisonExtractor.InvalidDraftException
                    || cause instanceof tools.jackson.core.JacksonException
                    || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException
                    || (cause instanceof java.io.InterruptedIOException && "timeout".equalsIgnoreCase(cause.getMessage()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean strict(ReportConfiguration configuration) {
        return configuration.retrieval() != null && configuration.retrieval().strict();
    }

    private static String comparisonScope(String dimension, ReportConfiguration configuration) {
        StringBuilder scope = new StringBuilder(dimension);
        if (configuration.instruction() != null && !configuration.instruction().isBlank()) {
            scope.append('\n').append(configuration.instruction());
        }
        configuration.sections().stream().filter(section -> "matrix".equals(section.sectionId()))
                .map(ReportConfiguration.Section::instruction).filter(Objects::nonNull)
                .forEach(instruction -> scope.append('\n').append(instruction));
        return scope.toString();
    }

    private static Cell missing(Source source) {
        return new Cell(source.documentId(), Status.NOT_FOUND, "本次未找到依据", List.of());
    }

    public static String sectionId(Kind kind) {
        return switch (kind) {
            case COMMON -> "commonalities";
            case POSSIBLE_CONFLICT -> "conflicts";
            case KEY_DATA -> "key_data";
            case DIFFERENT, INCOMPARABLE -> "differences";
        };
    }

    private static String matrixText(List<Source> sources, List<Row> rows) {
        List<String> lines = new ArrayList<>();
        for (Row row : rows) {
            for (int i = 0; i < sources.size(); i++) {
                Cell cell = row.cells().get(i);
                String values = cell.facts().stream().map(fact -> fact.quote() + (fact.verified() ? "" : "（未核验）"))
                        .collect(Collectors.joining("；"));
                lines.add(row.dimension() + " / " + sources.get(i).fileName() + "：" + values + cell.reason());
            }
        }
        return String.join("\n", lines);
    }

    private static VerifiedSummary summary(List<Conclusion> conclusions) {
        // Include at most one example of each kind, so a long numeric table cannot hide gaps or conflicts.
        Map<Kind, Conclusion> first = new LinkedHashMap<>();
        conclusions.forEach(c -> first.putIfAbsent(c.kind(), c));
        String text = first.values().stream().map(Conclusion::text).collect(Collectors.joining("\n"));
        List<String> ids = first.values().stream().flatMap(c -> c.claimIds().stream()).distinct().toList();
        return new VerifiedSummary(text.isEmpty() ? "各文档的取证情况见逐项对比。" : text, ids, List.of());
    }
}
