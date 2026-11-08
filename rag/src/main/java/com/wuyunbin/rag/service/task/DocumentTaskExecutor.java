package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.*;
import com.wuyunbin.rag.retrieval.ChunkRetriever;
import com.wuyunbin.rag.retrieval.DocumentRetriever;
import com.wuyunbin.rag.service.DocumentService;
import com.wuyunbin.rag.service.generation.DocumentSummaryGenerator;
import com.wuyunbin.rag.service.generation.GeneratedDocumentAssembler;
import com.wuyunbin.rag.service.generation.OutlineGenerator;
import com.wuyunbin.rag.service.generation.SectionGenerator;
import com.wuyunbin.rag.service.generation.model.GeneratedDocument;
import com.wuyunbin.rag.service.generation.model.GeneratedSection;
import com.wuyunbin.rag.service.generation.model.ReportPlan;
import com.wuyunbin.rag.service.generation.model.ReportSectionPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 文档任务执行器：负责"实际生成"的编排（与任务生命周期管理的 {@link DocumentTaskService} 分离）。
 *
 * <p>核心流程（Section-level RAG）：</p>
 * <pre>
 *   校验（由 Service 完成）
 *     → DocumentRetriever 取相关文档（限定 documentIds）
 *     → OutlineGenerator 生成大纲
 *     → 逐章节：ChunkRetriever（限定 documentIds）→ SectionGenerator
 *     → DocumentSummaryGenerator 生成整体摘要
 *     → GeneratedDocumentAssembler 组装（章节保序 + Evidence 去重）
 * </pre>
 *
 * <p>关键约束：所有检索（文档级与章节级）的 {@link RetrievalOptions#documentIds()}
 * 始终等于任务指定的文档集合，保证绝不引用未选择文档。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentTaskExecutor {

    private final DocumentRetriever documentRetriever;
    private final ChunkRetriever chunkRetriever;
    private final OutlineGenerator outlineGenerator;
    private final SectionGenerator sectionGenerator;
    private final DocumentSummaryGenerator summaryGenerator;
    private final GeneratedDocumentAssembler assembler;
    private final DocumentService documentService;

    /**
     * 复用既有 RAG 检索配置，不新增 task 专属检索参数。
     */
    @Value("${rag.chat.rag.top-k:8}")
    private int defaultTopK;

    @Value("${rag.chat.rag.similarity-threshold:0.5}")
    private double defaultThreshold;

    /**
     * 执行文档生成，返回结构化文档（尚未导出为文件）。
     * 任一章节生成失败将抛出异常，由 Service 转为任务 FAILED（不静默跳过章节）。
     */
    public GeneratedDocument execute(DocumentTask task) {
        Set<String> documentIds = new LinkedHashSet<>(task.getDocumentIds());
        RetrievalOptions options = new RetrievalOptions(defaultTopK, defaultThreshold, documentIds);

        // 1. 文档级检索（始终限定在任务指定文档内）
        List<RetrievedDocument> hitDocuments = documentRetriever.search(task.getInstruction(), options);
        List<RetrievedDocument> outlineDocuments = ensureAllSelectedDocuments(documentIds, hitDocuments);

        // 2. 生成大纲（结构化输出）
        ReportPlan plan = outlineGenerator.generate(task.getInstruction(), task.getType(), outlineDocuments);

        // 3. 逐章节：章节级 Chunk 检索 + LLM 生成（章节失败直接抛出，整任务 FAILED）
        List<GeneratedSection> sections = new ArrayList<>();
        for (ReportSectionPlan sectionPlan : plan.sections()) {
            String sectionQuery = buildSectionQuery(sectionPlan);
            List<RetrievedChunk> chunks = chunkRetriever.search(sectionQuery, options);
            GeneratedSection section = sectionGenerator.generate(task.getType(), sectionPlan, chunks);
            sections.add(section);
        }

        // 4. 整体摘要 + 组装（章节保序、Evidence 去重）
        String summary = summaryGenerator.generate(plan.title(), task.getInstruction(), sections);
        GeneratedDocument document = assembler.assemble(plan.title(), summary, sections);

        log.info("[DocumentTask] taskId={} 文档生成完成 title={} sections={} references={}",
                task.getTaskId(), document.title(), document.sections().size(), document.references().size());
        return document;
    }

    /**
     * 章节检索查询 = 章节标题 + 生成目的。
     */
    private String buildSectionQuery(ReportSectionPlan plan) {
        if (plan.purpose() == null || plan.purpose().isBlank()) {
            return plan.title();
        }
        return plan.title() + "\n" + plan.purpose();
    }

    /**
     * 保证大纲阶段能看到用户选择的全部文档：
     * 检索命中的文档用真实分数与预览；未命中的选定文档用元数据占位（score=0、无预览），
     * 避免因相似度阈值过滤导致大纲"丢失"用户显式选中的资料。
     */
    private List<RetrievedDocument> ensureAllSelectedDocuments(Set<String> selectedIds,
                                                               List<RetrievedDocument> hitDocuments) {
        Map<String, RetrievedDocument> byId = new LinkedHashMap<>();
        for (RetrievedDocument doc : hitDocuments) {
            // 防御性二次过滤：正常情况下 Milvus 的 documentIds 过滤已保证不会命中未选文档，
            // 这里再兜底一次，确保任何实现都不会把未选资料带入大纲。
            if (doc.documentId() != null && selectedIds.contains(doc.documentId())) {
                byId.putIfAbsent(doc.documentId(), doc);
            }
        }
        for (String id : selectedIds) {
            if (!byId.containsKey(id)) {
                DocumentMetadata metadata = documentService.getDocument(id);
                String fileName = metadata != null ? metadata.fileName() : id;
                byId.put(id, new RetrievedDocument(id, fileName, 0.0, 0, List.of()));
            }
        }
        return new ArrayList<>(byId.values());
    }
}
