package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.service.generation.model.GeneratedSection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于 Spring AI ChatClient 的文档摘要生成器（纯文本输出）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmDocumentSummaryGenerator implements DocumentSummaryGenerator {

    private static final String PROMPT_LOCATION = "classpath:prompts/document-task-summary.txt";

    private final ChatClient chatClient;
    private final PromptTemplates promptTemplates;

    @Override
    public String generate(String title, String instruction, List<GeneratedSection> sections) {
        String sectionTitles = sections == null ? "" : sections.stream()
                .map(GeneratedSection::title)
                .collect(Collectors.joining("、"));
        String prompt = PromptTemplates.render(promptTemplates.load(PROMPT_LOCATION), Map.of(
                "title", title == null ? "" : title,
                "instruction", instruction == null ? "" : instruction,
                "sectionTitles", sectionTitles
        ));
        try {
            String summary = chatClient.prompt().user(prompt).call().content();
            return summary == null ? "" : summary.strip();
        } catch (RuntimeException e) {
            // 摘要属于增强信息，失败不应导致整个任务失败
            log.warn("文档摘要生成失败，返回空摘要", e);
            return "";
        }
    }
}
