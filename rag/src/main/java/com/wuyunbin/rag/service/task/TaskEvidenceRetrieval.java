package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.ReportRetrievalSettings;
import com.wuyunbin.rag.dto.RetrievalOptions;
import com.wuyunbin.rag.dto.RetrievedChunk;
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
    private static final Pattern ITEM = Pattern.compile(
            "^\\s*(?:(?:[（(]?[0-9０-９]{1,3}|[一二三四五六七八九十]+)[、）)]\\s*"
                    + "|[0-9０-９]{1,3}[.．](?![0-9０-９])\\s*|[-*•]\\s+)(.+)$");
    private static final Pattern INTRO = Pattern.compile("^(?:分章要求|总要求)\\s*[:：]\\s*");
    private static final Pattern INLINE = Pattern.compile("逐项(?:提取|核对|回答|列出)([^。；;，,\\n]+、[^。；;，,\\n]+)");
    private static final Pattern TABLE = Pattern.compile("表\\s*(\\d+)(?!\\d)");
    private static final Set<String> QUESTION_WORDS = Set.of("什么", "多少", "哪个", "哪些", "是谁", "如何", "是否");

    private final RetrievalPipeline pipeline;
    private final ChunkTokenizer tokenizer;

    record Questions(List<String> items, String scope) {}

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
