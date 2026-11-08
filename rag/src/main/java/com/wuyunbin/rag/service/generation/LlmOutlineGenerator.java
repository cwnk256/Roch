package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.dto.DocumentTaskType;
import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.dto.RetrievedDocument;
import com.wuyunbin.rag.service.generation.model.ReportPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 基于 Spring AI ChatClient 的大纲生成器。
 *
 * <p>使用结构化输出 {@code .entity(ReportPlan.class)}（底层 BeanOutputConverter 自动约束 JSON），
 * 不手工解析 LLM 自由文本。生成后立即 {@link ReportPlan#validate()} 校验结构。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmOutlineGenerator implements OutlineGenerator {

    private static final String PROMPT_LOCATION = "classpath:prompts/document-task-outline.txt";

    /**
     * 每个文档用于大纲判断的内容预览切片数。
     */
    private static final int PREVIEW_CHUNK_PER_DOC = 2;
    /**
     * 每条预览切片截断长度，避免大纲阶段上下文膨胀。
     */
    private static final int PREVIEW_CHUNK_CHARS = 200;
    /**
     * 参与大纲提示的文档数量上限。
     */
    private static final int MAX_DOCUMENTS = 10;

    private final ChatClient chatClient;
    private final PromptTemplates promptTemplates;

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= max ? collapsed : collapsed.substring(0, max) + "…";
    }

    @Override
    public ReportPlan generate(String instruction, DocumentTaskType type, List<RetrievedDocument> documents) {
        String template = promptTemplates.load(PROMPT_LOCATION);
        String prompt = PromptTemplates.render(template, Map.of(
                "taskType", type.name(),
                "instruction", instruction,
                "documentSummaries", buildDocumentSummaries(documents)
        ));

        ReportPlan plan;
        try {
            plan = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(ReportPlan.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("调用大模型生成大纲失败", e);
        }
        if (plan == null) {
            throw new IllegalStateException("大纲生成结果为空");
        }
        plan.validate();
        log.info("大纲生成完成 type={} title={} sections={}", type, plan.title(), plan.sections().size());
        return plan;
    }

    /**
     * 构造文档元信息 + 少量内容预览，帮助 LLM 了解资料主题，但不塞入全部切片。
     */
    private String buildDocumentSummaries(List<RetrievedDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return "（未检索到相关文档元信息）";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (RetrievedDocument doc : documents) {
            if (index > MAX_DOCUMENTS) {
                break;
            }
            sb.append(index++).append(". 文件名：").append(doc.fileName())
                    .append("；文档ID：").append(doc.documentId())
                    .append("；相关度：").append(String.format("%.2f", doc.score()))
                    .append("；命中切片数：").append(doc.hitCount());

            List<RetrievedChunk> chunks = doc.matchedChunks();
            if (chunks != null && !chunks.isEmpty()) {
                sb.append("；内容预览：");
                int count = 0;
                for (RetrievedChunk chunk : chunks) {
                    if (count >= PREVIEW_CHUNK_PER_DOC) {
                        break;
                    }
                    sb.append(truncate(chunk.content(), PREVIEW_CHUNK_CHARS)).append(' ');
                    count++;
                }
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }
}
