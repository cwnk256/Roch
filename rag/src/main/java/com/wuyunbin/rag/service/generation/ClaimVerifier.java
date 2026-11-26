package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkEntity;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
import com.wuyunbin.rag.service.PdfDocumentParser.TableRow;
import com.wuyunbin.rag.service.generation.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ClaimVerifier {
    public record Citation(int source, String quote,
                           @com.fasterxml.jackson.annotation.JsonIgnore String cellLocation,
                           @com.fasterxml.jackson.annotation.JsonIgnore String nativeContext,
                           @com.fasterxml.jackson.annotation.JsonIgnore List<String> physicalFacts,
                           @com.fasterxml.jackson.annotation.JsonIgnore String tableCaption) {
        public Citation(int source, String quote, String cellLocation, String nativeContext) {
            this(source, quote, cellLocation, nativeContext, null, null);
        }

        public Citation(int source, String quote) { this(source, quote, null, null, null, null); }
    }

    public record AnchorSpan(String chunkId, String documentId, Integer pageStart, Integer pageEnd,
                             int start, int characters, Integer question) {
        public AnchorSpan(String chunkId, String documentId, Integer pageStart, Integer pageEnd,
                          int start, int characters) {
            this(chunkId, documentId, pageStart, pageEnd, start, characters, null);
        }

        AnchorSpan forQuestion(int number) {
            return new AnchorSpan(chunkId, documentId, pageStart, pageEnd, start, characters, number);
        }
    }

    public record EvidenceOptions(String prompt, Map<String, Citation> citations) {
        public List<AnchorSpan> spans(List<RetrievedChunk> selected) {
            List<AnchorSpan> spans = new ArrayList<>();
            for (Citation citation : citations.values()) {
                if (citation.source() < 1 || citation.source() > selected.size()) continue;
                RetrievedChunk hit = selected.get(citation.source() - 1);
                var span = NativeTextSpan.find(hit.content(), citation.quote());
                if (span != null) spans.add(new AnchorSpan(hit.chunkId(), hit.documentId(), hit.pageStart(), hit.pageEnd(),
                        span.start(), span.end() - span.start()));
            }
            return List.copyOf(spans);
        }
    }

    public EvidenceOptions evidenceOptions(List<RetrievedChunk> selected) {
        int budget = selected == null ? 0 : selected.stream().filter(Objects::nonNull)
                .map(RetrievedChunk::content).filter(Objects::nonNull).mapToInt(String::length).sum();
        return evidenceOptions(selected, "", Math.min(24000, Math.max(256, budget)));
    }

    public EvidenceOptions evidenceOptions(List<RetrievedChunk> selected, String query, int budget) {
        Map<String, Citation> options = new LinkedHashMap<>();
        StringBuilder prompt = new StringBuilder();
        if (selected == null || originals == null) return new EvidenceOptions("", Map.of());
        for (int i = 0; i < selected.size(); i++) {
            RetrievedChunk hit = selected.get(i);
            if (hit == null || hit.chunkId() == null || hit.content() == null) continue;
            var row = chunks.findById(hit.chunkId()).filter(r -> Objects.equals(r.getDocMd5(), hit.documentId())).orElse(null);
            if (row == null || row.getContent() == null) continue;
            int source = i + 1;
            Set<Citation> sourceOptions = new LinkedHashSet<>();
            String name = Objects.toString(row.getFileName(), "").toLowerCase(Locale.ROOT);
            boolean pdf = name.endsWith(".pdf");
            if (name.matches(".*\\.xlsx?")) {
                var sheetRows = originals.rows(hit.documentId()).stream()
                        .filter(r -> Objects.equals(r.sheet(), row.getSectionPath())).toList();
                var occurrences = sheetRows.stream().collect(java.util.stream.Collectors.groupingBy(
                        r -> compact(r.text()), java.util.stream.Collectors.counting()));
                for (var cell : sheetRows) {
                    if (cell.text().length() > 1000 || occurrences.get(compact(cell.text())) != 1) continue;
                    Pattern pattern = quotePattern(cell.text());
                    if (pattern.matcher(row.getContent()).find() && pattern.matcher(hit.content()).find()) {
                        sourceOptions.add(new Citation(source, cell.text(), cell.sheet() + "!" + cell.range(),
                                cell.header() + "\n" + cell.text(), null, null));
                    }
                }
                addOptions(options, sourceOptions);
                continue;
            }
            var tableRows = pdf && row.getPageStart() != null
                    ? originals.tableRows(hit.documentId()).stream().filter(t -> t.page() == row.getPageStart()).toList()
                    : List.<TableRow>of();
            String nativePage = pdf ? originals.nativePdfPage(hit.documentId(), row.getPageStart()) : null;
            String nativeText = pdf ? nativePage : originals.nativeOfficeOrText(hit.documentId());
            String selectedText = compact(hit.content());
            for (var table : tableRows) {
                if (table.facts().isEmpty() || !selectedText.contains(compact(table.row()))) continue;
                if (NativeTextSpan.find(row.getContent(), table.row()) == null) continue;

                var found = NativeTextSpan.find(nativePage, table.row());
                if (found != null && found.quote().length() <= 1000
                        && tableRows.stream().filter(t -> compact(t.row()).equals(compact(table.row()))).count() == 1) {
                    var physicalFacts = captionedPhysicalFacts(table);
                    sourceOptions.add(new Citation(source, found.quote(), null,
                            table.caption() + "\n" + String.join("；", physicalFacts), physicalFacts, table.caption()));
                }
            }
            String boundaries = "(?<=[。！？!?；;])|\\R+|(?<=\\.)\\s+(?=[A-Z])";
            var candidates = new LinkedHashSet<>(Arrays.asList(hit.content().split(boundaries)));
            if (nativePage != null) candidates.addAll(Arrays.asList(nativePage.split("(?<=[。！？!?；;])")));
            for (String sentence : candidates) {
                String candidate = sentence.strip().replaceFirst("^#{1,6}\\s+", "");
                if (candidate.length() < 2 || candidate.length() > 1000 || row.getContent().isBlank()) continue;
                if (pdf && overlapsPdfTable(candidate, tableRows)) continue;
                if (!selectedText.contains(compact(candidate)) || NativeTextSpan.find(row.getContent(), candidate) == null) continue;
                var span = NativeTextSpan.find(nativeText, candidate);
                if (span != null && span.isCompleteClause(nativeText, !pdf) && span.quote().length() <= 1000) sourceOptions.add(
                        new Citation(source, span.quote(), null, span.quote(), null, null));
            }
            if (nativePage != null && originals.hasInvisiblePdfText(hit.documentId(), row.getPageStart())) {
                addOcrLineOptions(sourceOptions, source, nativePage, hit.content(), row.getContent(), tableRows);
            }
            addOptions(options, sourceOptions);
        }
        var visible = new LinkedHashMap<String,Citation>();
        for (var entry : rankOptions(options, query)) {
            String line = renderOption(entry.getKey(), entry.getValue());
            if (prompt.length() + line.length() > budget) continue;
            visible.put(entry.getKey(), entry.getValue());
            prompt.append(line);
        }
        return new EvidenceOptions(prompt.toString(), Collections.unmodifiableMap(visible));
    }

    private static List<String> captionedPhysicalFacts(TableRow table) {
        if (!table.row().stripLeading().startsWith("领域汇总") || !table.caption().contains("研究前沿热度指数")) {
            return table.facts();
        }
        // 行名只写“领域汇总”时，用同一物理表标题中明确注明的指标补全事实。
        return table.facts().stream().map(fact -> fact.replace("的领域汇总", "的领域汇总热度指数")).toList();
    }

    private static void addOcrLineOptions(Set<Citation> options, int source, String page, String selected,
                                          String indexed, List<TableRow> tableRows) {
        // 跨行引文必须能同时在原页、存档切片和本轮入选文本中完整定位。
        var lines = page.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        String selectedText = compact(selected);
        String indexedText = compact(indexed);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            var span = alignedOcrSpan(line, page, selected, indexed, selectedText, indexedText, tableRows);
            if (span == null) continue;
            int last = i;
            while (last + 1 < lines.size() && ocrContinuation(lines.get(last), lines.get(last + 1))) {
                var extended = alignedOcrSpan(line + "\n" + lines.get(last + 1),
                        page, selected, indexed, selectedText, indexedText, tableRows);
                if (extended == null) break;
                span = extended;
                line = span.quote();
                last++;
            }
            String quote = span.quote();
            if (options.stream().noneMatch(citation -> citation.quote().equals(quote))) {
                String context = String.join("\n", lines.subList(Math.max(0, i - 1), Math.min(lines.size(), last + 2)));
                options.add(new Citation(source, quote, null, context));
            }
            i = last;
        }
    }

    private static NativeTextSpan alignedOcrSpan(String text, String page, String selected, String indexed,
                                                 String selectedText, String indexedText, List<TableRow> tableRows) {
        if (text.length() < 2 || text.length() > 1000) return null;
        String normalized = compact(text);
        if (!selectedText.contains(normalized) || !indexedText.contains(normalized) || overlapsPdfTable(text, tableRows)
                || NativeTextSpan.find(selected, text) == null || NativeTextSpan.find(indexed, text) == null) return null;
        return NativeTextSpan.find(page, text);
    }

    private static final Pattern OCR_QUALIFIER = Pattern.compile(
            "^(?:但|仅限|除非|前提是|(?i:but\\b|unless\\b|only\\s+if\\b|provided\\s+that\\b))");

    private static boolean ocrContinuation(String before, String after) {
        return "、，,：:".indexOf(before.charAt(before.length() - 1)) >= 0
                || OCR_QUALIFIER.matcher(after).find();
    }

    static String renderOption(String id, Citation citation) {
        return "[" + id + "] " + Objects.toString(citation.cellLocation(), "") + " " + renderPassage(citation) + "\n";
    }

    static List<Map.Entry<String, Citation>> rankOptions(Map<String, Citation> options, String query) {
        var ranked = new ArrayList<>(options.entrySet());
        var queryTerms = bigrams(query);
        if (!queryTerms.isEmpty()) ranked.sort(Comparator.comparingInt((Map.Entry<String, Citation> entry) ->
                relevance(entry.getValue().physicalFacts() == null && entry.getValue().cellLocation() == null
                        ? entry.getValue().quote() : entry.getValue().nativeContext(), queryTerms)).reversed());
        return ranked;
    }

    private static String renderPassage(Citation citation) {
        if (hasOcrContext(citation)) return citation.quote();
        if (citation.physicalFacts() == null) return Objects.requireNonNullElse(citation.nativeContext(), citation.quote());
        StringJoiner facts = new StringJoiner("；", Objects.toString(citation.tableCaption(), "") + "\n", "");
        for (int index = 0; index < citation.physicalFacts().size(); index++) {
            facts.add("(" + (index + 1) + ") " + citation.physicalFacts().get(index));
        }
        return facts.toString();
    }

    private static boolean hasOcrContext(Citation citation) {
        return citation.physicalFacts() == null && citation.cellLocation() == null
                && citation.nativeContext() != null && !citation.nativeContext().equals(citation.quote());
    }

    private static Set<String> bigrams(String text) {
        String normalized = compact(text);
        Set<String> terms = new HashSet<>();
        for (int i = 1; i < normalized.length(); i++) terms.add(normalized.substring(i-1, i+1));
        return terms;
    }

    private static int relevance(String text, Set<String> terms) {
        var found = bigrams(text);
        found.retainAll(terms);
        return found.size();
    }

    private static String compact(String text) {
        return WHITESPACE.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static boolean looksLikeTable(String text) {
        return Pattern.compile("[0-9]+(?:[.]\\d+)?\\s+[-+]?\\d+(?:[.]\\d+)?(?:\\s+[-+]?\\d+|$)").matcher(text).find()
                || text.contains("|");
    }

    private static boolean overlapsPdfTable(String candidate, List<TableRow> tableRows) {
        if (looksLikeTable(candidate)) return true;
        String normalized = compact(candidate);
        return tableRows.stream().map(row -> compact(row.row()))
                .anyMatch(row -> normalized.contains(row) || row.contains(normalized));
    }

    private static void addOptions(Map<String, Citation> options, Set<Citation> sourceOptions) {
        for (Citation citation : sourceOptions.stream().limit(512).toList()) {
            String id = "S" + citation.source() + "E" + (options.size() + 1);
            options.put(id, citation);
        }
    }

    private static Pattern quotePattern(String text) {
        String normalized = WHITESPACE.matcher(text).replaceAll(" ").trim();
        String expression = Arrays.stream(normalized.split(" ")).map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("\\s+"));
        if (!normalized.isEmpty() && Character.isDigit(normalized.codePointAt(0))) {
            expression = "(?<![\\d+−-])(?<!\\d[,.])" + expression;
        }
        if (!normalized.isEmpty() && Character.isDigit(normalized.codePointBefore(normalized.length()))) {
            expression += "(?!\\d|[,.]\\d)";
        }
        return Pattern.compile(expression, Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE);
    }
    public record VerifiedClaim(Claim claim, List<Evidence> evidences) {}
    private record Located(Evidence evidence, String context, String originalContext, boolean ocrText) {}

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private final DocumentChunkJpaRepository chunks;
    private final ClaimSupportJudge judge;
    private final OriginalPassages originals;

    @org.springframework.beans.factory.annotation.Autowired
    public ClaimVerifier(DocumentChunkJpaRepository chunks, ClaimSupportJudge judge, OriginalPassages originals) {
        this.chunks = chunks;
        this.judge = judge;
        this.originals = originals;
    }

    public ClaimVerifier(DocumentChunkJpaRepository chunks, ClaimSupportJudge judge) {
        this(chunks, judge, null);
    }

    public VerifiedClaim verify(String claimId, String text, List<Citation> citations,
                                List<RetrievedChunk> selected, Set<String> allowedDocumentIds) {
        return verify(claimId, text, citations, selected, allowedDocumentIds, false, null, false);
    }

    public VerifiedClaim verifyStrict(String claimId, String text, List<Citation> citations,
                                      List<RetrievedChunk> selected, Set<String> allowedDocumentIds) {
        return verify(claimId, text, citations, selected, allowedDocumentIds, true, null, false);
    }

    public VerifiedClaim verifyStrict(String claimId, String text, List<Citation> citations,
                                      List<RetrievedChunk> selected, Set<String> allowedDocumentIds,
                                      String requestedScope) {
        return verify(claimId, text, citations, selected, allowedDocumentIds, true, requestedScope, false);
    }

    public VerifiedClaim verifyAnchoredComparison(String claimId, String text, Citation citation,
                                                 List<RetrievedChunk> selected, Set<String> allowedDocumentIds,
                                                 String requestedScope, boolean literalFieldsProven) {
        return verify(claimId, text, Collections.singletonList(citation), selected, allowedDocumentIds,
                true, requestedScope, literalFieldsProven);
    }

    private VerifiedClaim verify(String claimId, String text, List<Citation> citations,
                                 List<RetrievedChunk> selected, Set<String> allowedDocumentIds,
                                 boolean strict, String requestedScope, boolean literalFieldsProven) {
        Map<String, Evidence> evidence = new LinkedHashMap<>();
        List<ClaimSupportJudge.Passage> passages = new ArrayList<>();
        List<StructuredEvidence.Passage> structured = new ArrayList<>();
        String failure = "陈述没有引用。";
        boolean invalid = false;
        boolean physicalProof = false;
        for (Citation citation : citations == null ? List.<Citation>of() : citations) {
            if (citation == null || selected == null || citation.source() < 1 || citation.source() > selected.size()) {
                invalid = true;
                failure = "引用编号未出现在本次上下文中。";
                continue;
            }
            RetrievedChunk hit = selected.get(citation.source() - 1);
            if (hit == null || hit.documentId() == null || allowedDocumentIds == null
                    || !allowedDocumentIds.contains(hit.documentId()) || hit.chunkId() == null || hit.chunkId().isBlank()) {
                invalid = true;
                failure = "引用不属于任务所选文档或缺少切片编号。";
                continue;
            }
            Located located = locate(hit, citation);
            Evidence snapshot = located.evidence();
            if (evidence.putIfAbsent(snapshot.evidenceId(), snapshot) != null) continue;
            if (snapshot.sourceStatus() == SourceStatus.MATCHED && citation.physicalFacts() != null) {
                String statement = compact(text).replaceFirst("[。.]$", "");
                if (!citation.physicalFacts().stream().map(ClaimVerifier::compact).anyMatch(statement::equals)) {
                    invalid = true;
                    failure = "陈述未与同一物理行列的对象、指标和数值逐项对应。";
                } else {
                    physicalProof = true;
                }
            }
            if (snapshot.sourceStatus() == SourceStatus.MATCHED) {
                passages.add(new ClaimSupportJudge.Passage(snapshot.quote(), located.context()));
                if (strict) structured.add(new StructuredEvidence.Passage(snapshot.quote(), located.context(),
                        located.originalContext(), located.ocrText()));
            } else {
                invalid = true;
                failure = snapshot.sourceStatus() == SourceStatus.QUOTE_MISMATCH
                        ? "引文与检索片段或存档原文不匹配。" : "无法读取原文件或核实切片归属。";
            }
        }
        ClaimSupportJudge.Judgment decision = new ClaimSupportJudge.Judgment(SupportStatus.INSUFFICIENT, failure);
        if (!invalid && !passages.isEmpty()) {
            try {
                var result = (physicalProof || literalFieldsProven) && (requestedScope == null || requestedScope.isBlank())
                        ? new ClaimSupportJudge.Judgment(SupportStatus.SUPPORTED, "同一原文记录逐项支持本条事实。")
                        : strict && requestedScope != null && !requestedScope.isBlank()
                            ? judge.judgeInScope(text, List.copyOf(passages), requestedScope)
                            : judge.judge(text, List.copyOf(passages));
                decision = result != null && result.status() != null && result.reason() != null && !result.reason().isBlank()
                        ? result : unavailableJudgment();
                if (strict && !literalFieldsProven && !physicalProof && decision.status() == SupportStatus.SUPPORTED) {
                    String insufficient = StructuredEvidence.insufficiency(text, structured);
                    if (insufficient != null) {
                        decision = new ClaimSupportJudge.Judgment(SupportStatus.INSUFFICIENT, insufficient);
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("内容支持判断失败 claimId={}", claimId, ex);
                decision = unavailableJudgment();
            }
        }
        return new VerifiedClaim(new Claim(claimId, text, List.copyOf(evidence.keySet()), decision.status(),
                decision.reason(), true), List.copyOf(evidence.values()));
    }

    private ClaimSupportJudge.Judgment unavailableJudgment() {
        return new ClaimSupportJudge.Judgment(SupportStatus.INSUFFICIENT, "自动内容判断未完成，需人工复核。");
    }

    private Located locate(RetrievedChunk hit, Citation citation) {
        String proposed = citation.quote();
        DocumentChunkEntity row = null;
        try {
            row = chunks.findById(hit.chunkId()).filter(c -> hit.documentId().equals(c.getDocMd5())).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("来源回查失败 chunkId={}", hit.chunkId(), ex);
        }
        boolean available = row != null && row.getContent() != null;
        String hash = available ? hashText(row.getContent()) : null;
        String quote = null;
        String originalContext = null;
        String nativeLocation = null;
        boolean ocrText = false;
        if (available && citation.nativeContext() != null && citation.cellLocation() == null && originals != null) {
            boolean pdf = Objects.toString(row.getFileName(), "").toLowerCase(Locale.ROOT).endsWith(".pdf");
            String page = pdf ? originals.nativePdfPage(hit.documentId(), row.getPageStart()) : originals.nativeOfficeOrText(hit.documentId());
            available = page != null && !page.isEmpty();
            var matched = NativeTextSpan.find(page, proposed);
            if (matched != null && matched.quote().equals(proposed) && NativeTextSpan.find(hit.content(), proposed) != null
                    && NativeTextSpan.find(row.getContent(), proposed) != null) {
                quote = matched.quote();
                originalContext = page;
                ocrText = pdf && originals.hasInvisiblePdfText(hit.documentId(), row.getPageStart());
                nativeLocation = (pdf ? "PDF 页内字符 [" : "原文字符 [") + matched.start() + "," + matched.end() + ")";
                String tableId = TableQuestionScope.captionId(citation.tableCaption());
                if (tableId != null) nativeLocation += "（表" + tableId + "）";

            }
        } else if (available && proposed != null && !proposed.isBlank() && proposed.length() <= 1000) {
            var pattern = quotePattern(proposed);
            var sourceMatch = pattern.matcher(row.getContent());
            if (hit.content() != null && pattern.matcher(hit.content()).find() && sourceMatch.find()) {
                if (citation.cellLocation() != null && originals != null) {
                    String sheetPath = row.getSectionPath();
                    var cell = originals.rows(hit.documentId()).stream().filter(r -> r.text().equals(proposed)
                            && Objects.equals(sheetPath, r.sheet())
                            && (r.sheet() + "!" + r.range()).equals(citation.cellLocation())).findFirst();
                    if (cell.isPresent()) {
                        quote = cell.get().text();
                        nativeLocation = citation.cellLocation();
                        originalContext = "工作表 " + cell.get().sheet() + "\n" + cell.get().header() + "\n" + quote;
                    }
                } else if (originals == null) {
                    quote = sourceMatch.group();
                } else {
                    var original = originals.find(hit.documentId(), row.getPageStart(), pattern);
                    available = original.available();
                    quote = original.quote();
                    originalContext = original.context();
                }
            }
        }
        SourceStatus status = !available ? SourceStatus.SOURCE_UNAVAILABLE :
                quote == null ? SourceStatus.QUOTE_MISMATCH : SourceStatus.MATCHED;
        String attempted = quote == null ? proposed : null;
        String id = "E-" + hashText(String.join("\u0000", hit.documentId(), hit.chunkId(), status.name(),
                Objects.toString(hash, "") + Objects.toString(citation.cellLocation(), ""), Objects.toString(quote == null ? proposed : quote, ""),
                available ? Objects.toString(row.getPipelineVersion(), "") : ""));
        Evidence snapshot = new Evidence(hit.documentId(), available ? row.getFileName() : hit.fileName(),
                hit.chunkId(), citation.cellLocation() != null && quote != null ? citation.cellLocation() : available ? row.getSectionPath() : null, available ? row.getPageStart() : null,
                available ? row.getPageEnd() : null, hit.score(), id, quote, status,
                available ? row.getPipelineVersion() : null, hash, available ? row.getChunkIndex() : null, attempted, nativeLocation);
        String context = citation.cellLocation() != null ? originalContext : available ? row.getContent() : null;
        if (quote != null && hasOcrContext(citation)) context = citation.nativeContext();
        return new Located(snapshot, context, originalContext, ocrText);
    }

    public static String hashText(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
