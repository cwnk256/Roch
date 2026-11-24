package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.*;
import com.wuyunbin.rag.retrieval.ChunkRetriever;
import com.wuyunbin.rag.retrieval.DocumentRetriever;
import com.wuyunbin.rag.retrieval.RetrievalMode;
import com.wuyunbin.rag.service.DocumentService;
import com.wuyunbin.rag.service.comparison.ComparisonGenerator;
import com.wuyunbin.rag.service.generation.DocumentSummaryGenerator;
import com.wuyunbin.rag.service.generation.GeneratedDocumentAssembler;
import com.wuyunbin.rag.service.generation.OutlineGenerator;
import com.wuyunbin.rag.service.generation.LlmSectionGenerator;
import com.wuyunbin.rag.service.generation.SectionGenerator;
import com.wuyunbin.rag.service.generation.model.GeneratedDocument;
import com.wuyunbin.rag.service.generation.model.GeneratedSection;
import com.wuyunbin.rag.service.generation.model.Claim;
import com.wuyunbin.rag.service.generation.model.Evidence;
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
    private final ComparisonGenerator comparisonGenerator;
    private final TaskEvidenceRetrieval taskRetrieval;
    private final TaskEvidenceLog evidenceLog;
    private final DocumentTaskProperties properties;
    private static final int QUESTIONS_PER_BATCH = 4;

    /**
     * 复用既有 RAG 检索配置，不新增 task 专属检索参数。
     */
    @Value("${rag.chat.rag.top-k:8}")
    private int defaultTopK;

    @Value("${rag.chat.rag.similarity-threshold:0.5}")
    private double defaultThreshold;

    /**
     * 执行文档生成，返回结构化文档（尚未导出为文件）。
     */
    public GeneratedDocument execute(DocumentTask task) {
        if (task.getType() == DocumentTaskType.COMPARE) {
            return comparisonGenerator.generate(task, defaultTopK, defaultThreshold);
        }
        Set<String> documentIds = new LinkedHashSet<>(task.getDocumentIds());
        ReportConfiguration configuration = task.getConfiguration();
        ReportRetrievalSettings settings = configuration == null ? null : configuration.retrieval();
        var options = new RetrievalOptions(settings == null ? defaultTopK : settings.topK(),
                settings == null ? defaultThreshold : settings.threshold(), documentIds,
                settings == null ? null : settings.hybrid() ? RetrievalMode.HYBRID : settings.baselineMode());
        ReportPlan plan;
        if (configuration != null && configuration.templateId() != null) {
            plan = configuration.toPlan();
        } else {
            List<RetrievedDocument> hits = documentRetriever.search(task.getInstruction(), options);
            plan = outlineGenerator.generate(task.getInstruction(), task.getType(), ensureAllSelectedDocuments(documentIds, hits));
        }
        List<GeneratedSection> sections = new ArrayList<>();
        GenerationProgress progress = new GenerationProgress();
        for (int index = 0; index < plan.sections().size(); index++) {
            ReportSectionPlan section = plan.sections().get(index);
            if (settings != null && settings.hybrid()) {
                sections.add(generateEnhancedSection(task, section, index + 1, documentIds, settings, progress));
            } else {
                long start = System.nanoTime();
                List<RetrievedChunk> hits = chunkRetriever.search(buildSectionQuery(section), options);
                int budget = settings == null ? properties.getMaxContextChars() : settings.reportMaxChars();
                List<RetrievedChunk> selected = LlmSectionGenerator.selectWithinBudget(hits, documentIds, budget);
                evidenceLog.event(task.getTaskId(), index + 1, 1, "context", "selected", hits.size(), selected, start);
                sections.add(settings == null
                        ? sectionGenerator.generate(task.getType(), section, hits, documentIds)
                        : sectionGenerator.generateBatch(task.getType(), section, selected, documentIds,
                                section.title(), false));
            }
        }
        if (progress.lastFailure != null && progress.completed == 0) {
            throw progress.lastFailure;
        }

        // 4. 整体摘要 + 组装（章节保序、Evidence 去重）
        var summary = summaryGenerator.generateSummary(plan.title(), task.getInstruction(), sections);
        GeneratedDocument document = assembler.assemble(plan.title(), summary, sections).withConfiguration(configuration);

        log.info("[DocumentTask] taskId={} 文档生成完成 title={} sections={} references={}",
                task.getTaskId(), document.title(), document.sections().size(), document.references().size());
        return document;
    }

    private GeneratedSection generateEnhancedSection(DocumentTask task, ReportSectionPlan section, int sectionNumber,
                                                       Set<String> scope, ReportRetrievalSettings settings,
                                                       GenerationProgress progress) {
        var questions = TaskEvidenceRetrieval.questions(section.purpose());
        List<String> items = settings.splitQuestions() ? questions.items() : List.of();
        if (items.isEmpty()) {
            return generateBatch(task, section, sectionNumber, 1, scope, settings, settings.reportMaxChars(),
                    List.of(buildSectionQuery(section)), false, progress);
        }
        List<GeneratedSection> batches = new ArrayList<>();
        for (int start = 0; start < items.size(); start += QUESTIONS_PER_BATCH) {
            int end = Math.min(start + QUESTIONS_PER_BATCH, items.size());
            List<String> batch = items.subList(start, end);
            StringBuilder purpose = new StringBuilder(questions.scope()).append("\n只回答以下问题，按题号顺序逐项给出事实：");
            for (int index = 0; index < batch.size(); index++) {
                purpose.append('\n').append(index + 1).append(". ").append(batch.get(index));
            }
            int endBudget = (int) ((long) settings.reportMaxChars() * end / items.size());
            int startBudget = (int) ((long) settings.reportMaxChars() * start / items.size());
            var batchPlan = new ReportSectionPlan(section.title(), purpose.toString());
            batches.add(generateBatch(task, batchPlan, sectionNumber, batches.size() + 1, scope, settings,
                    endBudget - startBudget, batch, true, progress));
        }
        Map<String, Evidence> snapshots = new LinkedHashMap<>();
        List<Claim> claims = new ArrayList<>();
        StringJoiner content = new StringJoiner("\n");
        for (GeneratedSection batch : batches) {
            content.add(batch.content());
            claims.addAll(batch.claims());
            batch.evidences().forEach(evidence -> snapshots.putIfAbsent(evidence.evidenceId(), evidence));
        }
        return new GeneratedSection(section.title(), content.toString(), List.copyOf(snapshots.values()), claims);
    }

    private GeneratedSection generateBatch(DocumentTask task, ReportSectionPlan section, int sectionNumber, int batchNumber,
                                             Set<String> scope, ReportRetrievalSettings settings, int budget,
                                             List<String> queries, boolean split, GenerationProgress progress) {
        String batchId = "C-" + sectionNumber + "-" + batchNumber + "-";
        long start = System.nanoTime();
        List<List<RetrievedChunk>> candidateLists = new ArrayList<>();
        RuntimeException retrievalFailure = null;
        int successfulQueries = 0;
        for (String query : queries) {
            try {
                candidateLists.add(taskRetrieval.hybrid(query, scope, settings));
                successfulQueries++;
            } catch (RuntimeException ex) {
                evidenceLog.event(task.getTaskId(), sectionNumber, batchNumber, "retrieval", "failed", 0, List.of(), start);
                if (!settings.strict()) throw ex;
                retrievalFailure = ex;
                candidateLists.add(List.of());
            }
        }
        if (successfulQueries == 0) {
            progress.lastFailure = retrievalFailure;
            return missingBatch(section, batchId, queries, split, "检索失败");
        }
        int candidates = candidateLists.stream().mapToInt(List::size).sum();
        List<RetrievedChunk> selected = split
                ? TaskEvidenceRetrieval.balance(candidateLists, scope, budget)
                : LlmSectionGenerator.selectWithinBudget(candidateLists.getFirst().stream().limit(settings.topK()).toList(),
                        scope, budget);
        evidenceLog.event(task.getTaskId(), sectionNumber, batchNumber, "context",
                selected.isEmpty() ? "empty" : "selected", candidates, selected, start);
        if (selected.isEmpty() && split) {
            progress.completed++;
            return missingBatch(section, batchId, queries, true, "未检索到可用依据");
        }
        long generationStart = System.nanoTime();
        try {
            GeneratedSection result = sectionGenerator.generateBatch(task.getType(), section, selected, scope, batchId, settings.strict());
            progress.completed++;
            evidenceLog.event(task.getTaskId(), sectionNumber, batchNumber, "generation", "completed",
                    candidates, List.of(), generationStart);
            return result;
        } catch (SectionGenerator.BatchFailure ex) {
            evidenceLog.event(task.getTaskId(), sectionNumber, batchNumber, "generation", ex.failureKind().name(),
                    candidates, List.of(), generationStart);
            if (!settings.strict()) throw ex;
            progress.lastFailure = ex;
            return missingBatch(section, batchId, queries, split, "本批次生成失败");
        }
    }

    private static GeneratedSection missingBatch(ReportSectionPlan section, String id, List<String> queries,
                                                  boolean split, String reason) {
        var claims = new ArrayList<Claim>();
        if (split) {
            for (String question : queries) {
                claims.add(Claim.unsupported(id + (claims.size() + 1), question + "（" + reason + "）", reason));
            }
        } else {
            claims.add(Claim.unsupported(id + "1", section.title() + "（" + reason + "）", reason));
        }
        return new GeneratedSection(section.title(),
                String.join("\n", claims.stream().map(Claim::text).toList()), List.of(), claims);
    }

    private static class GenerationProgress {
        int completed;
        RuntimeException lastFailure;
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
