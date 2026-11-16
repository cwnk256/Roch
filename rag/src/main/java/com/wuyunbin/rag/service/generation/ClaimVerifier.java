package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkEntity;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
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
    public record Citation(int source, String quote) {}
    public record VerifiedClaim(Claim claim, List<Evidence> evidences) {}
    private record Located(Evidence evidence, String context) {}

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
        Map<String, Evidence> evidence = new LinkedHashMap<>();
        List<ClaimSupportJudge.Passage> passages = new ArrayList<>();
        String failure = "陈述没有引用。";
        boolean invalid = false;
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
            Located located = locate(hit, citation.quote());
            Evidence snapshot = located.evidence();
            if (evidence.putIfAbsent(snapshot.evidenceId(), snapshot) != null) continue;
            if (snapshot.sourceStatus() == SourceStatus.MATCHED) {
                passages.add(new ClaimSupportJudge.Passage(snapshot.quote(), located.context()));
            } else {
                invalid = true;
                failure = snapshot.sourceStatus() == SourceStatus.QUOTE_MISMATCH
                        ? "引文与检索片段或存档原文不匹配。" : "无法读取原文件或核实切片归属。";
            }
        }
        ClaimSupportJudge.Judgment decision = new ClaimSupportJudge.Judgment(SupportStatus.INSUFFICIENT, failure);
        if (!invalid && !passages.isEmpty()) {
            try {
                var result = judge.judge(text, List.copyOf(passages));
                decision = result != null && result.status() != null && result.reason() != null && !result.reason().isBlank()
                        ? result : unavailableJudgment();
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

    private Located locate(RetrievedChunk hit, String proposed) {
        DocumentChunkEntity row = null;
        try {
            row = chunks.findById(hit.chunkId()).filter(c -> hit.documentId().equals(c.getDocMd5())).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("来源回查失败 chunkId={}", hit.chunkId(), ex);
        }
        boolean available = row != null && row.getContent() != null;
        String hash = available ? hashText(row.getContent()) : null;
        String quote = null;
        if (available && proposed != null && !proposed.isBlank() && proposed.length() <= 1000) {
            String normalized = WHITESPACE.matcher(proposed).replaceAll(" ").trim();
            String[] tokens = normalized.split(" ");
            String expression = Arrays.stream(tokens).map(Pattern::quote)
                    .collect(java.util.stream.Collectors.joining("\\s+"));
            if (!normalized.isEmpty() && Character.isDigit(normalized.codePointAt(0))) {
                expression = "(?<![\\d+−-])(?<!\\d[,.])" + expression;
            }
            if (!normalized.isEmpty() && Character.isDigit(normalized.codePointBefore(normalized.length()))) {
                expression += "(?!\\d|[,.]\\d)";
            }
            var pattern = Pattern.compile(expression, Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE);
            var sourceMatch = pattern.matcher(row.getContent());
            if (!normalized.isEmpty() && hit.content() != null && pattern.matcher(hit.content()).find() && sourceMatch.find()) {
                if (originals == null) {
                    quote = sourceMatch.group();
                } else {
                    var original = originals.find(hit.documentId(), row.getPageStart(), pattern);
                    available = original.available();
                    quote = original.quote();
                }
            }
        }
        SourceStatus status = !available ? SourceStatus.SOURCE_UNAVAILABLE :
                quote == null ? SourceStatus.QUOTE_MISMATCH : SourceStatus.MATCHED;
        String attempted = quote == null ? proposed : null;
        String id = "E-" + hashText(String.join("\u0000", hit.documentId(), hit.chunkId(), status.name(),
                Objects.toString(hash, ""), Objects.toString(quote == null ? proposed : quote, ""),
                available ? Objects.toString(row.getPipelineVersion(), "") : ""));
        Evidence snapshot = new Evidence(hit.documentId(), available ? row.getFileName() : hit.fileName(),
                hit.chunkId(), available ? row.getSectionPath() : null, available ? row.getPageStart() : null,
                available ? row.getPageEnd() : null, hit.score(), id, quote, status,
                available ? row.getPipelineVersion() : null, hash, available ? row.getChunkIndex() : null, attempted);
        return new Located(snapshot, available ? row.getContent() : null);
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
