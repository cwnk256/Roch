package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.dto.DocumentTaskType;
import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.service.generation.model.*;
import com.wuyunbin.rag.service.task.DocumentTaskProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class LlmSectionGenerator implements SectionGenerator {
    public record DraftClaim(String text, List<ClaimVerifier.Citation> citations) {}
    public record SectionDraft(List<DraftClaim> claims, String unattributedText) {}

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
        String prefix = "C-" + ClaimVerifier.hashText(plan.title()).substring(0, 16) + "-";
        Map<String,Evidence> snapshots = new LinkedHashMap<>();
        List<Claim> claims = new ArrayList<>();
        if (!selected.isEmpty()) {
            StringBuilder context = new StringBuilder();
            for (int i = 0; i < selected.size(); i++) {
                context.append("\n[资料 ").append(i + 1).append("]\n").append(selected.get(i).content()).append('\n');
            }
            String prompt = PromptTemplates.render(templates.load("classpath:prompts/document-task-section.txt"),
                    Map.of("documentKind", type == DocumentTaskType.REPORT ? "正式报告" : "资料总结",
                            "sectionTitle", plan.title(), "sectionPurpose", Objects.toString(plan.purpose(), ""),
                            "evidenceContext", context.toString()));
            SectionDraft draft = chatClient.prompt().user(prompt).call().entity(SectionDraft.class);
            if (draft == null) throw new IllegalStateException("章节生成结果为空：" + plan.title());
            for (DraftClaim item : draft.claims() == null ? List.<DraftClaim>of() : draft.claims()) {
                if (item == null || item.text() == null || item.text().isBlank()) continue;
                String id = prefix + (claims.size() + 1);
                if (verifier == null) {
                    claims.add(Claim.unsupported(id, item.text(), "未配置来源核验器。"));
                    continue;
                }
                var verified = verifier.verify(id, item.text().strip(), item.citations(), selected, allowedDocumentIds);
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
