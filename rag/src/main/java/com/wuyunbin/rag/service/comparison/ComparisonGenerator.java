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
                cells.add(collect(source, dimension, config, prefix, topK, threshold, run));
            }
            rows.add(new Row(dimension, List.copyOf(cells)));
        }
        List<Conclusion> conclusions = ComparisonRules.conclusions(sources, rows);
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

    private Cell collect(Source source, String dimension, ReportConfiguration config, String prefix,
                         int topK, double threshold, Run run) {
        Set<String> scope = Set.of(source.documentId());
        List<RetrievedChunk> selected;
        run.retrievalCalls++;
        try {
            var hits = retrieval.search(dimension, new RetrievalOptions(Math.min(8, Math.max(1, topK)), threshold, scope));
            selected = LlmSectionGenerator.selectWithinBudget(hits, scope, Math.min(8000, properties.getMaxContextChars()));
        } catch (RuntimeException ex) {
            log.warn("对比检索失败 document={} dimension={}", source.documentId(), dimension, ex);
            return new Cell(source.documentId(), Status.RETRIEVAL_FAILED, "检索失败", List.of());
        }
        if (selected.isEmpty()) return missing(source);

        var explicit = ExplicitComparisonFacts.select(dimension, selected);
        List<ComparisonCandidate> candidates = explicit.facts();
        boolean incomplete = false;
        if (explicit.needsModel()) {
            run.modelCalls++;
            try {
                candidates = ExplicitComparisonFacts.merge(explicit, extractor.extract(dimension, config, selected));
            } catch (RuntimeException ex) {
                log.warn("对比事实提取失败 document={} dimension={}", source.documentId(), dimension, ex);
                if (candidates.isEmpty()) {
                    return new Cell(source.documentId(), Status.EXTRACTION_FAILED, "模型未能完成事实提取", List.of());
                }
                incomplete = true;
            }
        }
        if (candidates.isEmpty()) return missing(source);
        List<Fact> facts = new ArrayList<>();
        for (ComparisonCandidate candidate : candidates) {
            String claimId = prefix + (facts.size() + 1);
            var verified = verifier.verify(claimId, candidate.statement(),
                    List.of(new ClaimVerifier.Citation(candidate.source(), candidate.quote())), selected, scope);
            if (verified.evidences().stream().anyMatch(e -> e.sourceStatus() == SourceStatus.MATCHED)) {
                // The shared verifier invokes one support judgment for a single matched citation.
                run.modelCalls++;
            }
            boolean supported = verified.claim().supportStatus() == SupportStatus.SUPPORTED;
            var originalQuote = verified.evidences().stream().filter(e -> e.sourceStatus() == SourceStatus.MATCHED)
                    .map(Evidence::quote).findFirst().orElse(candidate.quote());
            Fact fact = ComparisonRules.fact(claimId, candidate, supported, originalQuote);
            facts.add(fact);
            run.claims.add(verified.claim());
            verified.evidences().forEach(e -> run.snapshots.putIfAbsent(e.evidenceId(), e));
        }
        boolean found = facts.stream().anyMatch(Fact::verified);
        return new Cell(source.documentId(), found ? Status.FOUND : Status.UNVERIFIED,
                incomplete ? "部分原文的事实提取失败" : facts.stream().allMatch(Fact::verified) ? ""
                        : "部分或全部原文、事实或比较字段未通过核验", List.copyOf(facts));
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
