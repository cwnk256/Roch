package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.dto.DocumentTaskType;
import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.service.generation.model.Evidence;
import com.wuyunbin.rag.service.generation.model.GeneratedSection;
import com.wuyunbin.rag.service.generation.model.ReportSectionPlan;
import com.wuyunbin.rag.service.task.DocumentTaskProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Spring AI ChatClient 的章节生成器。
 *
 * <p>处理流程：检索切片 → 去重 → 按 {@code maxContextChars} 预算选取 → 拼接证据上下文
 * → LLM 基于证据写正文 → 用"实际进入上下文的切片"构造 Evidence。</p>
 *
 * <p>资料不足时由 Prompt 约束 LLM 输出固定说明，不允许凭空生成事实。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmSectionGenerator implements SectionGenerator {

    private static final String PROMPT_LOCATION = "classpath:prompts/document-task-section.txt";

    private final ChatClient chatClient;
    private final PromptTemplates promptTemplates;
    private final DocumentTaskProperties properties;

    @Override
    public GeneratedSection generate(DocumentTaskType type,
                                     ReportSectionPlan sectionPlan,
                                     List<RetrievedChunk> evidenceChunks) {
        // 只选取真正进入 LLM 上下文的切片作为证据，保证引用诚实可追溯
        List<RetrievedChunk> selected = selectWithinBudget(evidenceChunks);
        String documentKind = type == DocumentTaskType.REPORT ? "正式报告" : "资料总结";
        String prompt = PromptTemplates.render(promptTemplates.load(PROMPT_LOCATION), Map.of(
                "documentKind", documentKind,
                "sectionTitle", sectionPlan.title(),
                "sectionPurpose", sectionPlan.purpose() == null ? "" : sectionPlan.purpose(),
                "evidenceContext", buildEvidenceContext(selected)
        ));

        long start = System.nanoTime();
        String content;
        try {
            content = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
        } catch (RuntimeException e) {
            throw new IllegalStateException("章节生成失败: " + sectionPlan.title(), e);
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("章节生成内容为空: " + sectionPlan.title());
        }

        List<Evidence> evidences = selected.stream().map(Evidence::from).toList();
        log.info("章节生成完成 section={} retrievedChunks={} usedChunks={} latencyMs={}",
                sectionPlan.title(),
                evidenceChunks == null ? 0 : evidenceChunks.size(),
                selected.size(), latencyMs);
        return new GeneratedSection(sectionPlan.title(), content.strip(), evidences);
    }

    /**
     * 按相似度降序（Retriever 已排序）去重 chunkId，并在 maxContextChars 预算内选取切片；
     * 至少保留 1 条，保证单章节有证据输入。
     */
    private List<RetrievedChunk> selectWithinBudget(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, RetrievedChunk> unique = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk.chunkId() != null) {
                unique.putIfAbsent(chunk.chunkId(), chunk);
            }
        }

        int budget = Math.max(1000, properties.getMaxContextChars());
        List<RetrievedChunk> selected = new ArrayList<>();
        int used = 0;
        for (RetrievedChunk chunk : unique.values()) {
            int len = chunk.content() == null ? 0 : chunk.content().length();
            if (selected.isEmpty() || used + len <= budget) {
                selected.add(chunk);
                used += len;
            }
            if (used >= budget) {
                break;
            }
        }
        return selected;
    }

    /**
     * 把证据切片拼成带序号与来源标注的上下文本。
     */
    private String buildEvidenceContext(List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return "（本章节未检索到任何相关证据资料）";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (RetrievedChunk chunk : chunks) {
            sb.append("[资料 ").append(index++).append(']')
                    .append(" 来源文件：").append(chunk.fileName());
            if (chunk.section() != null && !chunk.section().isBlank()) {
                sb.append("；章节：").append(chunk.section());
            }
            sb.append(System.lineSeparator())
                    .append(chunk.content() == null ? "" : chunk.content())
                    .append(System.lineSeparator()).append(System.lineSeparator());
        }
        return sb.toString();
    }
}
