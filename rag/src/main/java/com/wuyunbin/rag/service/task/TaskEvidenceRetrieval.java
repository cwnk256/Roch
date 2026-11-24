package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.ReportRetrievalSettings;
import com.wuyunbin.rag.dto.RetrievalOptions;
import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkEntity;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
import com.wuyunbin.rag.retrieval.RetrievalMode;
import com.wuyunbin.rag.retrieval.RetrievalPipeline;
import com.wuyunbin.rag.service.ChunkTokenizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class TaskEvidenceRetrieval {
    private static final int CANDIDATES_PER_CHANNEL = 20;
    private static final int REPORT_ANCHOR_CANDIDATES = 32;
    private static final Pattern ITEM = Pattern.compile(
            "^\\s*(?:(?:[（(]?[0-9０-９]{1,3}|[一二三四五六七八九十]+)[、）)]\\s*"
                    + "|[0-9０-９]{1,3}[.．](?![0-9０-９])\\s*|[-*•]\\s+)(.+)$");
    private static final Pattern INTRO = Pattern.compile("^(?:分章要求|总要求)\\s*[:：]\\s*");
    private static final Pattern INLINE = Pattern.compile("逐项(?:提取|核对|回答|列出)([^。；;，,\\n]+、[^。；;，,\\n]+)");
    private static final Pattern TABLE = Pattern.compile("表\\s*(\\d+)(?!\\d)");
    private static final Pattern TABLE_NUMBER = Pattern.compile("(?i)(?:表\\h*|\\btable\\h+)(\\d+)(?!\\d)");
    private static final Pattern TABLE_CAPTION = Pattern.compile(
            "(?im)(?:^|[。.;；])\\h*(?:表\\h*|table\\h+)(\\d+)(?!\\d)");
    private static final Pattern ANCHOR_WORD = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private static final Pattern MEASURABLE_QUESTION = Pattern.compile(
            "(?i)多少|多大|几(?:名|位|个|次|分)|第几|排名|名次|得分|比率|比例|how\\s+(?:many|much)|\\brank\\b");
    private static final Pattern VALUE = Pattern.compile("\\d+(?:[.,]\\d+)?|第[一二三四五六七八九十]+");
    private static final Set<String> QUESTION_WORDS = Set.of("什么", "多少", "哪个", "哪些", "是谁", "如何", "是否");
    private static final Set<String> ANCHOR_STOP_WORDS = Set.of(
            "请问", "什么", "多少", "哪个", "哪些", "如何", "是否", "第几", "how", "many", "much",
            "which", "what", "was", "were", "the", "of", "in", "for", "by");
    private static final Comparator<AnchoredPassage> ANCHORED_PRIORITY =
            Comparator.comparing(AnchoredPassage::table).reversed()
                    .thenComparing(Comparator.comparingInt(AnchoredPassage::coverage).reversed())
                    .thenComparingInt(AnchoredPassage::characters)
                    .thenComparing(match -> match.chunks().getFirst().chunkId());

    private final RetrievalPipeline pipeline;
    private final ChunkTokenizer tokenizer;
    private final DocumentChunkJpaRepository chunkRepository;

    record Questions(List<String> items, String scope) {}

    record Supplement(List<RetrievedChunk> selected, int candidateCount) {}

    Supplement supplement(List<String> queries, List<RetrievedChunk> original, Set<String> scope, int budget) {
        int remaining = budget - original.stream().mapToInt(chunk -> chunk.content().length()).sum();
        if (remaining <= 0 || scope.isEmpty()) return new Supplement(original, 0);
        Map<String, RetrievedChunk> selected = new LinkedHashMap<>();
        original.forEach(chunk -> selected.put(chunk.chunkId(), chunk));
        int candidateCount = 0;
        for (String query : queries) {
            if (remaining <= 0) break;
            var anchor = reportAnchor(query);
            if (anchor == null) continue;
            List<AnchoredPassage> candidates = new ArrayList<>();
            for (DocumentChunkEntity row : chunkRepository.searchReportAnchors(anchor.keywordQuery(), scope,
                    REPORT_ANCHOR_CANDIDATES)) {
                AnchoredPassage match = anchoredPassage(row, anchor, scope);
                if (match != null) candidates.add(match);
            }
            candidateCount += candidates.stream().mapToInt(match -> match.chunks().size()).sum();
            candidates.sort(ANCHORED_PRIORITY);
            // 拆开的表头与数据行有足够预算时一同加入。原批次已有最佳证据时此题无需补充。
            for (AnchoredPassage match : candidates) {
                List<RetrievedChunk> additions = match.chunks().stream()
                        .filter(chunk -> !selected.containsKey(chunk.chunkId())).toList();
                int size = additions.stream().mapToInt(chunk -> chunk.content().length()).sum();
                if (size > remaining) continue;
                additions.forEach(chunk -> selected.put(chunk.chunkId(), chunk));
                remaining -= size;
                break;
            }
        }
        return new Supplement(List.copyOf(selected.values()), candidateCount);
    }

    private record ReportAnchor(String keywordQuery, String tableNumber, List<String> leadingTerms,
                                List<String> terms, String label) {}

    private record AnchoredPassage(List<RetrievedChunk> chunks, boolean table, int coverage) {
        int characters() {
            return chunks.stream().mapToInt(chunk -> chunk.content().length()).sum();
        }
    }

    private ReportAnchor reportAnchor(String query) {
        String normalized = Normalizer.normalize(query, Normalizer.Form.NFKC);
        var table = TABLE_NUMBER.matcher(normalized);
        String tableNumber = table.find() ? table.group(1) : null;
        if (tableNumber == null && !MEASURABLE_QUESTION.matcher(normalized).find()) return null;
        List<String> terms = Arrays.stream(tokenizer.tokenize(query).split("\\s+"))
                .map(TaskEvidenceRetrieval::compact)
                .filter(word -> ANCHOR_WORD.matcher(word).matches() && !ANCHOR_STOP_WORDS.contains(word))
                .distinct().toList();
        List<String> leading = terms.stream().filter(word -> !word.equals(tableNumber)).limit(3).toList();
        if (tableNumber != null) {
            String beforeTable = compact(normalized.substring(0, table.start()));
            leading = leading.stream().filter(beforeTable::contains).toList();
        } else if (leading.size() < 2) {
            return null;
        }
        List<String> searchTerms = new ArrayList<>(leading);
        if (tableNumber != null) searchTerms.add(tableNumber);
        int possessive = normalized.indexOf('的');
        String prefix = possessive < 0 ? "" : compact(normalized.substring(0, possessive));
        String label = terms.stream().filter(prefix::endsWith).max(Comparator.comparingInt(String::length)).orElse("");
        return new ReportAnchor(String.join(" & ", searchTerms), tableNumber, leading, terms, label);
    }

    private AnchoredPassage anchoredPassage(DocumentChunkEntity row, ReportAnchor anchor, Set<String> scope) {
        RetrievedChunk lead = storedChunk(row);
        if (!usable(lead, scope) || lead.pageStart() == null || lead.pageStart() < 1) return null;
        String content = Normalizer.normalize(lead.content(), Normalizer.Form.NFKC);
        var captions = TABLE_CAPTION.matcher(content).results().toList();
        if (!captions.isEmpty()) {
            List<AnchoredPassage> aligned = new ArrayList<>();
            for (int index = 0; index < captions.size(); index++) {
                var caption = captions.get(index);
                if (anchor.tableNumber() != null && !anchor.tableNumber().equals(caption.group(1))) continue;
                boolean lastCaption = index + 1 == captions.size();
                int end = lastCaption ? content.length() : captions.get(index + 1).start();
                String block = content.substring(caption.start(), end);
                String[] parts = block.split("\\R", 2);
                if (!anchor.leadingTerms().stream().allMatch(compact(parts[0])::contains)) continue;
                String data = parts.length == 2 ? parts[1] : "";
                if (tableRows(data)) {
                    if (matchesTableData(anchor, data)) {
                        aligned.add(new AnchoredPassage(List.of(lead), true, coverage(anchor, block)));
                    }
                } else if (lastCaption) {
                    var continuation = adjacentTableRows(row, lead, anchor, scope, block);
                    if (continuation != null) aligned.add(continuation);
                }
            }
            return aligned.stream().min(ANCHORED_PRIORITY).orElse(null);
        }
        if (anchor.tableNumber() == null && containsLabel(anchor, content)
                && anchor.leadingTerms().stream().allMatch(compact(content)::contains)) {
            int coverage = coverage(anchor, content);
            if (coverage >= Math.min(anchor.terms().size(), anchor.leadingTerms().size() + 1)
                    && content.lines().anyMatch(line -> containsLabel(anchor, line) && VALUE.matcher(line).find())) {
                return new AnchoredPassage(List.of(lead), false, coverage);
            }
        }
        return null;
    }

    private AnchoredPassage adjacentTableRows(DocumentChunkEntity row, RetrievedChunk lead, ReportAnchor anchor,
                                              Set<String> scope, String block) {
        if (!Objects.equals(lead.pageStart(), lead.pageEnd()) || lead.chunkIndex() == null) return null;
        for (DocumentChunkEntity neighbor : chunkRepository
                .findTop4ByDocMd5AndPageStartAndChunkIndexOrderByChunkIdAsc(
                        lead.documentId(), lead.pageStart(), lead.chunkIndex() + 1)) {
            RetrievedChunk next = storedChunk(neighbor);
            if (!usable(next, scope) || !Objects.equals(next.documentId(), lead.documentId())
                    || !Objects.equals(next.pageStart(), lead.pageStart())
                    || !Objects.equals(next.pageEnd(), lead.pageEnd())
                    || !Objects.equals(next.chunkIndex(), lead.chunkIndex() + 1)
                    || !Objects.equals(neighbor.getPipelineVersion(), row.getPipelineVersion())
                    || !Objects.equals(next.section(), lead.section())) continue;
            String rows = Normalizer.normalize(next.content(), Normalizer.Form.NFKC);
            if (!TABLE_CAPTION.matcher(rows).find() && tableRows(rows) && matchesTableData(anchor, rows)) {
                return new AnchoredPassage(List.of(lead, next), true, coverage(anchor, block + "\n" + rows));
            }
        }
        return null;
    }

    private static boolean tableRows(String body) {
        return body.lines().anyMatch(line -> {
            var values = VALUE.matcher(line);
            return values.find() && (line.contains("|") || values.find());
        });
    }

    private static boolean containsLabel(ReportAnchor anchor, String text) {
        return anchor.label().isEmpty() || compact(text).contains(anchor.label());
    }

    private static boolean matchesTableData(ReportAnchor anchor, String text) {
        if (!anchor.label().isEmpty()) return containsLabel(anchor, text);
        List<String> qualifiers = anchor.terms().stream()
                .filter(term -> !anchor.leadingTerms().contains(term) && !term.equals(anchor.tableNumber())).toList();
        return qualifiers.isEmpty() || qualifiers.stream().anyMatch(compact(text)::contains);
    }

    private static int coverage(ReportAnchor anchor, String text) {
        String compactText = compact(text);
        return (int) anchor.terms().stream().filter(compactText::contains).count();
    }

    private static RetrievedChunk storedChunk(DocumentChunkEntity row) {
        return new RetrievedChunk(row.getChunkId(), row.getDocMd5(), row.getFileName(), row.getContent(),
                row.getSectionPath(), row.getPageStart(), row.getPageEnd(), row.getChunkIndex(), 0);
    }

    static Questions questions(String purpose) {
        if (purpose == null || purpose.isBlank()) return new Questions(List.of(), "");
        Set<String> items = new LinkedHashSet<>();
        Set<String> scope = new LinkedHashSet<>();
        for (String line : purpose.lines().toList()) {
            String text = INTRO.matcher(line.strip()).replaceFirst("");
            var item = ITEM.matcher(text);
            if (item.matches()) {
                items.add(item.group(1).strip());
            } else if (!text.isBlank()) {
                scope.add(text);
            }
        }
        if (items.isEmpty()) {
            var inline = INLINE.matcher(purpose);
            while (inline.find()) {
                for (String topic : inline.group(1).split("、|以及|和(?=[^、]+$)")) {
                    if (!topic.isBlank()) items.add(topic.strip());
                }
            }
            if (!items.isEmpty()) {
                scope = new LinkedHashSet<>(scope.stream().map(text -> INLINE.matcher(text).replaceAll(""))
                        .filter(text -> !text.isBlank()).toList());
            }
        }
        return new Questions(List.copyOf(items), String.join("\n", scope));
    }

    public List<RetrievedChunk> hybrid(String query, Set<String> documentIds, ReportRetrievalSettings settings) {
        if (documentIds.isEmpty()) throw new IllegalArgumentException("检索需要指定文档范围");
        var options = new RetrievalOptions(CANDIDATES_PER_CHANNEL, settings.threshold(), documentIds, RetrievalMode.HYBRID);
        List<RetrievedChunk> candidates = pipeline.search(query, options).stream()
                .filter(hit -> usable(hit, documentIds))
                .toList();
        return settings.splitQuestions() ? preferExactTerms(query, candidates) : candidates;
    }

    private List<RetrievedChunk> preferExactTerms(String query, List<RetrievedChunk> candidates) {
        List<String> terms = Arrays.stream(tokenizer.tokenize(query).split("\\s+"))
                .map(TaskEvidenceRetrieval::compact)
                .filter(term -> term.length() > 1 && !QUESTION_WORDS.contains(term))
                .filter(term -> term.codePoints().anyMatch(Character::isLetterOrDigit))
                .distinct().toList();
        List<Pattern> tables = TABLE.matcher(query).results()
                .map(match -> Pattern.compile("表" + match.group(1) + "(?!\\d)")).toList();
        // 保留融合顺序作为同分决策；表号须完整匹配，表 11 不应命中表 110。
        Map<String, Integer> coverage = new HashMap<>();
        for (RetrievedChunk hit : candidates) {
            String passage = compact(hit.content() + " " + Objects.toString(hit.section(), ""));
            int matched = terms.stream().filter(passage::contains).mapToInt(term -> Math.min(4, term.length())).sum();
            matched += 12 * (int) tables.stream().filter(table -> table.matcher(passage).find()).count();
            coverage.put(hit.chunkId(), matched);
        }
        return candidates.stream().sorted(Comparator.<RetrievedChunk>comparingInt(hit -> coverage.get(hit.chunkId()))
                .reversed()).toList();
    }

    static List<RetrievedChunk> balance(List<List<RetrievedChunk>> hits, Set<String> scope, int budget) {
        Map<String, RetrievedChunk> selected = new LinkedHashMap<>();
        Set<Page> seenPages = new HashSet<>();
        int remaining = budget;
        for (boolean firstPassage : List.of(true, false)) {
            for (List<RetrievedChunk> candidates : hits) {
                for (RetrievedChunk hit : candidates) {
                    if (!usable(hit, scope)) continue;
                    if (selected.containsKey(hit.chunkId())) continue;
                    var page = new Page(hit.documentId(), hit.pageStart());
                    if (!firstPassage && page.number() != null && seenPages.contains(page)) continue;
                    if (hit.content().length() > remaining) continue;
                    selected.put(hit.chunkId(), hit);
                    seenPages.add(page);
                    remaining -= hit.content().length();
                    break;
                }
            }
        }
        return List.copyOf(selected.values());
    }

    private record Page(String document, Integer number) {}

    private static boolean usable(RetrievedChunk hit, Set<String> scope) {
        return hit != null && hit.documentId() != null && scope.contains(hit.documentId())
                && hit.chunkId() != null && !hit.chunkId().isBlank()
                && hit.content() != null && !hit.content().isBlank();
    }

    private static String compact(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", "");
    }
}
