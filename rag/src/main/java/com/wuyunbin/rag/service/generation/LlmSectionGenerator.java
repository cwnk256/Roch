package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.dto.DocumentTaskType;
import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.service.generation.model.*;
import com.wuyunbin.rag.service.task.DocumentTaskProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
public class LlmSectionGenerator implements SectionGenerator {
    public record DraftClaim(String text, List<ClaimVerifier.Citation> citations) {}
    public record SectionDraft(List<DraftClaim> claims, String unattributedText) {}
    private record OrderedClaim(int question, DraftClaim claim) {}

    private static final JsonMapper DRAFT_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final ChatClient chatClient;
    private final PromptTemplates templates;
    private final DocumentTaskProperties properties;
    private final ClaimVerifier verifier;

    @Autowired
    public LlmSectionGenerator(ChatClient chatClient, PromptTemplates templates, DocumentTaskProperties properties,
                               ClaimVerifier verifier) {
        this.chatClient = chatClient;
        this.templates = templates;
        this.properties = properties;
        this.verifier = verifier;
    }

    public LlmSectionGenerator(ChatClient chatClient, PromptTemplates templates, DocumentTaskProperties properties) {
        this(chatClient, templates, properties, null);
    }

    @Override
    public GeneratedSection generate(DocumentTaskType type, ReportSectionPlan plan, List<RetrievedChunk> chunks) {
        Set<String> scope = chunks == null ? Set.of() : chunks.stream().filter(Objects::nonNull)
                .map(RetrievedChunk::documentId).filter(Objects::nonNull).collect(Collectors.toSet());
        return generate(type, plan, chunks, scope);
    }

    @Override
    public GeneratedSection generate(DocumentTaskType type, ReportSectionPlan plan, List<RetrievedChunk> chunks,
                                     Set<String> allowedDocumentIds) {
        List<RetrievedChunk> selected = selectWithinBudget(chunks, allowedDocumentIds, properties.getMaxContextChars());
        return generateSection(type, plan, selected, allowedDocumentIds, plan.title(), false, null);
    }

    @Override
    public GeneratedSection generateBatch(DocumentTaskType type, ReportSectionPlan plan, List<RetrievedChunk> chunks,
                                           Set<String> allowedDocumentIds, String batchId, boolean strict) {
        // 批次上下文已由执行器分配预算，这里仅沿用来源过滤和切片去重。
        List<RetrievedChunk> selected = selectWithinBudget(chunks, allowedDocumentIds, Integer.MAX_VALUE);
        return generateSection(type, plan, selected, allowedDocumentIds, batchId, strict, null);
    }

    @Override
    public GeneratedSection generateAnchoredBatch(DocumentTaskType type, ReportSectionPlan plan,
                                                 List<RetrievedChunk> chunks, Set<String> allowed, String batchId,
                                                 int budget, Consumer<List<ClaimVerifier.AnchorSpan>> onAnchors) {
        var selected = selectWithinBudget(chunks, allowed, Integer.MAX_VALUE);
        var options = verifier.evidenceOptions(selected, plan.title() + "\n" + plan.purpose(), budget);
        onAnchors.accept(options.spans(selected));
        return generateSection(type, plan, selected, allowed, batchId, true, options);
    }

    private GeneratedSection generateSection(DocumentTaskType type, ReportSectionPlan plan, List<RetrievedChunk> selected,
                                             Set<String> allowedDocumentIds, String claimNamespace, boolean strict, ClaimVerifier.EvidenceOptions options) {
        String prefix = "C-" + ClaimVerifier.hashText(claimNamespace).substring(0, 16) + "-";
        Map<String,Evidence> snapshots = new LinkedHashMap<>();
        List<Claim> claims = new ArrayList<>();
        if (!selected.isEmpty() && (options == null || !options.citations().isEmpty())) {
            StringBuilder context = new StringBuilder();
            for (int i = 0; i < selected.size(); i++) {
                context.append("\n[资料 ").append(i + 1).append("]\n").append(selected.get(i).content()).append('\n');
            }
            String template = options != null ? "classpath:prompts/document-task-section-anchored.txt" : strict ? "classpath:prompts/document-task-section-strict.txt"
                    : "classpath:prompts/document-task-section.txt";
            String prompt = PromptTemplates.render(templates.load(template),
                    Map.of("documentKind", type == DocumentTaskType.REPORT ? "正式报告" : "资料总结",
                            "sectionTitle", plan.title(), "sectionPurpose", Objects.toString(plan.purpose(), ""),
                            "evidenceContext", options == null ? context.toString() : options.prompt()));
            SectionDraft draft = strict ? generateStrictDraft(prompt, options, options == null ? null : new TableQuestionScope(plan.purpose()))
                    : chatClient.prompt().user(prompt).call().entity(SectionDraft.class);
            if (draft == null) throw new IllegalStateException("章节生成结果为空：" + plan.title());
            for (DraftClaim item : draft.claims() == null ? List.<DraftClaim>of() : draft.claims()) {
                if (item == null || item.text() == null || item.text().isBlank()) continue;
                String id = prefix + (claims.size() + 1);
                if (verifier == null) {
                    claims.add(Claim.unsupported(id, item.text(), "未配置来源核验器。"));
                    continue;
                }
                var verified = strict
                        ? verifier.verifyStrict(id, item.text().strip(), item.citations(), selected, allowedDocumentIds)
                        : verifier.verify(id, item.text().strip(), item.citations(), selected, allowedDocumentIds);
                claims.add(verified.claim());
                verified.evidences().forEach(e -> snapshots.putIfAbsent(e.evidenceId(), e));
            }
            if (draft.unattributedText() != null && !draft.unattributedText().isBlank()) {
                claims.add(Claim.unsupported(prefix + (claims.size() + 1), draft.unattributedText().strip(),
                        "正文未关联来源。"));
            }
        }
        if (claims.isEmpty()) {
            claims.add(Claim.unsupported(prefix + "1", "本次未找到可引用的事实。", "本章没有可核验的陈述。"));
        }
        String content = claims.stream().map(Claim::text).collect(Collectors.joining("\n"));
        return new GeneratedSection(plan.title(), content, List.copyOf(snapshots.values()), claims);
    }

    private SectionDraft generateStrictDraft(String prompt, ClaimVerifier.EvidenceOptions options, TableQuestionScope scope) {
        try {
            return requestStrictDraft(prompt, options, scope);
        } catch (BatchFailure first) {
            try {
                return requestStrictDraft(prompt, options, scope);
            } catch (BatchFailure second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    private SectionDraft requestStrictDraft(String prompt, ClaimVerifier.EvidenceOptions options, TableQuestionScope scope) {
        String response;
        try {
            response = chatClient.prompt().user(prompt).call().content();
        } catch (RuntimeException failure) {
            if (isTimeout(failure)) throw new BatchFailure(BatchFailureKind.TIMEOUT, failure);
            throw failure;
        }

        if (response == null || response.isBlank()) throw new BatchFailure(BatchFailureKind.INVALID_RESPONSE, null);
        String json = response.strip();
        if (json.startsWith("```") && json.endsWith("```")) {
            int lineBreak = json.indexOf('\n');
            if (lineBreak > 0 && lineBreak < json.length() - 3) {
                json = json.substring(lineBreak + 1, json.length() - 3).strip();
            }
        }
        JsonNode draft;
        try {
            draft = DRAFT_MAPPER.readTree(json);
        } catch (JacksonException failure) {
            throw new BatchFailure(BatchFailureKind.INVALID_RESPONSE, failure);
        }
        JsonNode claims = draft == null ? null : draft.get("claims");
        if (claims == null || !claims.isArray()) throw new BatchFailure(BatchFailureKind.INVALID_RESPONSE, null);

        List<OrderedClaim> ordered = new ArrayList<>();
        for (JsonNode claim : claims) {
            String text = stringValue(claim.get("text"));
            Integer question = positiveInteger(claim.get("question"));
            List<ClaimVerifier.Citation> citations = options == null ? readCitations(claim.get("citations"))
                    : readAnchors(claim.get("evidenceIds"), options, scope, question);
            JsonNode tableFact = claim.get("tableFact");
            if (options != null && tableFact != null && !tableFact.isNull()) {
                String proven = resolveTableFact(positiveInteger(tableFact), citations);
                if (proven != null) {
                    text = proven;
                } else {
                    citations = Collections.singletonList(null);
                    if (text == null || text.isBlank()) text = "指定的表格事实无法回查。";
                }
            }
            if (text == null || text.isBlank()) continue;
            ordered.add(new OrderedClaim(question == null ? Integer.MAX_VALUE : question, new DraftClaim(text, citations)));
        }
        if (!claims.isEmpty() && ordered.isEmpty()) throw new BatchFailure(BatchFailureKind.INVALID_RESPONSE, null);
        ordered.sort(Comparator.comparingInt(OrderedClaim::question));
        return new SectionDraft(ordered.stream().map(OrderedClaim::claim).toList(), stringValue(draft.get("unattributedText")));
    }

    private static String resolveTableFact(Integer number, List<ClaimVerifier.Citation> citations) {
        if (number == null || citations.size() != 1 || citations.getFirst() == null) return null;
        var facts = citations.getFirst().physicalFacts();
        return facts == null || number > facts.size() ? null : facts.get(number - 1);
    }

    private static List<ClaimVerifier.Citation> readAnchors(JsonNode ids, ClaimVerifier.EvidenceOptions options, TableQuestionScope scope, Integer question) {
        if (ids == null || !ids.isArray()) return Collections.singletonList(null);
        var citations = new ArrayList<ClaimVerifier.Citation>();
        for (JsonNode id : ids) {
            var candidate = id.isString() ? options.citations().get(id.asString()) : null;
            citations.add(scope.allows(question, candidate) ? candidate : null);
        }
        return citations;
    }

    private static List<ClaimVerifier.Citation> readCitations(JsonNode citations) {
        if (citations == null || citations.isNull()) return List.of();
        if (!citations.isArray()) return Collections.singletonList(null);
        List<ClaimVerifier.Citation> result = new ArrayList<>();
        for (JsonNode citation : citations) {
            Integer source = positiveInteger(citation.get("source"));
            String quote = stringValue(citation.get("quote"));
            // 保留无效位置，让核验器单独降级此事实并保留其余合法的来源快照。
            result.add(source == null ? null : new ClaimVerifier.Citation(source, quote));
        }
        return result;
    }

    private static String stringValue(JsonNode value) {
        return value != null && value.isString() ? value.asString() : null;
    }

    private static Integer positiveInteger(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0
                ? value.intValue() : null;
    }

    private static boolean isTimeout(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) return false;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException || current instanceof InterruptedIOException
                    && "timeout".equalsIgnoreCase(current.getMessage())) return true;
        }
        return false;
    }

    public static List<RetrievedChunk> selectWithinBudget(List<RetrievedChunk> chunks, Set<String> allowed, int maxChars) {
        if (chunks == null || allowed == null || allowed.isEmpty()) return List.of();
        int remaining = Math.max(1, maxChars);
        Set<String> seen = new HashSet<>();
        List<RetrievedChunk> selected = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk == null || chunk.documentId() == null || !allowed.contains(chunk.documentId())
                    || chunk.chunkId() == null || chunk.chunkId().isBlank() || chunk.content() == null
                    || chunk.content().isBlank() || !seen.add(chunk.chunkId())) continue;
            String content = chunk.content();
            if (content.length() > remaining) {
                content = content.substring(0, remaining);
                if (Character.isHighSurrogate(content.charAt(content.length() - 1))) {
                    content = content.substring(0, content.length() - 1);
                }
                if (!content.isBlank()) selected.add(new RetrievedChunk(chunk.chunkId(), chunk.documentId(),
                        chunk.fileName(), content, chunk.section(), chunk.pageStart(), chunk.pageEnd(),
                        chunk.chunkIndex(), chunk.score()));
                break;
            }
            selected.add(chunk);
            remaining -= content.length();
            if (remaining == 0) break;
        }
        return selected;
    }
}
