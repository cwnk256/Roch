package com.wuyunbin.rag.service.comparison;

import com.wuyunbin.rag.dto.ReportConfiguration;
import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.service.generation.PromptTemplates;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ComparisonExtractor {
    public record Extraction(List<ComparisonCandidate> facts) {}
    static final int MAX_FACTS = 3;
    private final ChatClient chatClient;
    private final PromptTemplates templates;

    public List<ComparisonCandidate> extract(String dimension, ReportConfiguration configuration,
                                             List<RetrievedChunk> chunks) {
        StringBuilder input = new StringBuilder("比较维度：").append(dimension)
                .append("\n用户总要求：").append(configuration.instruction());
        for (var section : configuration.sections()) {
            input.append("\n").append(section.title()).append("：").append(section.instruction());
        }
        for (int i = 0; i < chunks.size(); i++) {
            input.append("\n[资料 ").append(i + 1).append("]\n").append(chunks.get(i).content());
        }
        Extraction result = chatClient.prompt().system(templates.load("classpath:prompts/document-task-compare.txt"))
                .user(input.toString()).call().entity(Extraction.class);
        if (result == null || result.facts() == null || result.facts().size() > MAX_FACTS
                || result.facts().stream().anyMatch(fact -> fact == null || fact.quote() == null
                || fact.quote().isBlank() || fact.quote().length() > 1000)) {
            throw new IllegalStateException("比较事实提取结果格式不合法");
        }
        return result.facts();
    }
}
