package com.wuyunbin.rag.service.comparison;

import com.wuyunbin.rag.dto.ReportConfiguration;
import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.service.generation.PromptTemplates;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class ComparisonExtractor {
    public record Extraction(List<ComparisonCandidate> facts) {}
    public record DraftExtraction(List<DraftFact> facts) {}
    public record DraftFact(Integer source, String quote, String object, String period, String condition,
                            String metric, String rawValue, String unit) {
        ComparisonCandidate candidate() {
            int citation = source == null || quote == null || quote.isBlank() || quote.length() > 1000 ? 0 : source;
            return new ComparisonCandidate(citation, Objects.toString(quote, ""), object, period,
                    condition, metric, rawValue, unit);
        }
    }
    static final int MAX_FACTS = 3;
    private final ChatClient chatClient;
    private final PromptTemplates templates;

    public List<ComparisonCandidate> extract(String dimension, ReportConfiguration configuration,
                                             List<RetrievedChunk> chunks) {
        Extraction result = chatClient.prompt().system(templates.load("classpath:prompts/document-task-compare.txt"))
                .user(input(dimension, configuration, chunks, false)).call().entity(Extraction.class);
        if (result == null || result.facts() == null || result.facts().size() > MAX_FACTS
                || result.facts().stream().anyMatch(fact -> fact == null || fact.quote() == null
                || fact.quote().isBlank() || fact.quote().length() > 1000)) {
            throw new IllegalStateException("比较事实提取结果格式不合法");
        }
        return result.facts();
    }

    public List<ComparisonCandidate> extractStrict(String dimension, ReportConfiguration configuration,
                                                   List<RetrievedChunk> chunks) {
        var result = chatClient.prompt().system(templates.load("classpath:prompts/document-task-compare.txt"))
                .user(input(dimension, configuration, chunks, true)).call().entity(DraftExtraction.class);
        if (result == null || result.facts() == null || (!result.facts().isEmpty() && result.facts().stream().allMatch(Objects::isNull))) {
            throw new InvalidDraftException();
        }
        return result.facts().stream().filter(Objects::nonNull).limit(MAX_FACTS).map(DraftFact::candidate).toList();
    }

    static class InvalidDraftException extends IllegalStateException {
        InvalidDraftException() {
            super("比较事实提取结果格式不合法");
        }
    }

    private String input(String dimension, ReportConfiguration configuration, List<RetrievedChunk> chunks, boolean strict) {
        StringBuilder input = new StringBuilder("比较维度：").append(dimension)
                .append("\n用户总要求：").append(configuration.instruction());
        for (var section : configuration.sections()) {
            if (strict && !"matrix".equals(section.sectionId())) continue;
            input.append("\n").append(section.title()).append("：").append(section.instruction());
        }
        if (strict) {
            input.append("\n仅取当前比较维度的事实，禁止引入其它维度的数据。仅给有逐字原文依据的事实，")
                    .append("按相关性排序，最多三条。source 必须是资料栏的正整数编号。");
        }
        for (int i = 0; i < chunks.size(); i++) {
            input.append("\n[资料 ").append(i + 1).append("]\n").append(chunks.get(i).content());
        }
        return input.toString();
    }
}
