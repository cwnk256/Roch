package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.service.generation.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmDocumentSummaryGenerator implements DocumentSummaryGenerator {
    public record SummaryItem(String claimId, String text) {}
    public record SummaryDraft(List<SummaryItem> items) {}

    private final ChatClient chatClient;
    private final PromptTemplates promptTemplates;

    @Override
    public String generate(String title, String instruction, List<GeneratedSection> sections) {
        return generateSummary(title, instruction, sections).text();
    }

    @Override
    public VerifiedSummary generateSummary(String title, String instruction, List<GeneratedSection> sections) {
        Map<String,Claim> known = new LinkedHashMap<>();
        if (sections != null) {
            sections.stream().flatMap(section -> section.claims().stream())
                    .filter(claim -> claim.supportStatus() == SupportStatus.SUPPORTED)
                    .forEach(claim -> known.put(claim.claimId(), claim));
        }
        if (known.isEmpty()) return new VerifiedSummary("本次没有得到充分支持的章节事实。", List.of(), List.of());
        String facts = known.values().stream().map(c -> c.claimId() + " " + c.text()).collect(Collectors.joining("\n"));
        try {
            String prompt = PromptTemplates.render(promptTemplates.load("classpath:prompts/document-task-summary.txt"),
                    Map.of("title", Objects.toString(title, ""), "instruction", Objects.toString(instruction, ""),
                            "facts", facts));
            var draft = chatClient.prompt().user(prompt).call().entity(SummaryDraft.class);
            if (draft != null && draft.items() != null) {
                VerifiedSummary result = resolve(draft.items(), known);
                if (!result.claimIds().isEmpty()) return result;
            }
        } catch (RuntimeException ex) {
            log.warn("摘要筛选未完成，使用章节中已有的核验事实", ex);
        }
        return resolve(known.values().stream().limit(3).map(c -> new SummaryItem(c.claimId(), c.text())).toList(), known);
    }

    static VerifiedSummary resolve(List<SummaryItem> items, Map<String,Claim> known) {
        List<Claim> additional = new ArrayList<>();
        Map<String,String> lines = new LinkedHashMap<>();
        for (SummaryItem item : items) {
            if (item == null) continue;
            Claim claim = known.get(item.claimId());
            String text = Objects.toString(item.text(), "").strip();
            if (claim != null && (text.isEmpty() || claim.text().equals(text))) {
                lines.putIfAbsent(claim.claimId(), claim.text());
            } else if (!text.isEmpty()) {
                Claim extra = Claim.unsupported("S-" + (additional.size() + 1), text, "摘要内容与引用的已核验事实不一致或没有有效引用。");
                additional.add(extra);
                lines.put(extra.claimId(), text);
            }
        }
        return new VerifiedSummary(String.join("\n", lines.values()), List.copyOf(lines.keySet()), additional);
    }
}
