package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.*;
import com.wuyunbin.rag.retrieval.ChunkRetriever;
import com.wuyunbin.rag.retrieval.DocumentRetriever;
import com.wuyunbin.rag.service.DocumentService;
import com.wuyunbin.rag.service.comparison.ComparisonGenerator;
import com.wuyunbin.rag.service.generation.DocumentSummaryGenerator;
import com.wuyunbin.rag.service.generation.GeneratedDocumentAssembler;
import com.wuyunbin.rag.service.generation.OutlineGenerator;
import com.wuyunbin.rag.service.generation.SectionGenerator;
import com.wuyunbin.rag.service.generation.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DocumentTaskExecutor 单测：
 * <ul>
 *   <li>文档级与章节级检索都强制携带完整 documentIds（绝不引用未选文档的核心保证）；</li>
 *   <li>检索器即使误返回未选文档也会被防御性过滤，不带入大纲；</li>
 *   <li>章节严格按大纲顺序生成；Evidence 由真实入参切片构造、最终去重。</li>
 * </ul>
 */
class DocumentTaskExecutorTest {

    private static final Set<String> SELECTED = Set.of("docA", "docB");
    private DocumentRetriever documentRetriever;
    private ChunkRetriever chunkRetriever;
    private OutlineGenerator outlineGenerator;
    private SectionGenerator sectionGenerator;
    private DocumentSummaryGenerator summaryGenerator;
    private DocumentService documentService;
    private DocumentTaskExecutor executor;
    private TaskEvidenceRetrieval taskRetrieval;
    private TaskEvidenceLog evidenceLog;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        documentRetriever = mock(DocumentRetriever.class);
        chunkRetriever = mock(ChunkRetriever.class);
        outlineGenerator = mock(OutlineGenerator.class);
        sectionGenerator = mock(SectionGenerator.class);
        summaryGenerator = mock(DocumentSummaryGenerator.class);
        documentService = mock(DocumentService.class);
        // 组装器使用真实实现，连带验证章节保序 + Evidence 去重
        GeneratedDocumentAssembler assembler = new GeneratedDocumentAssembler();
        taskRetrieval = mock(TaskEvidenceRetrieval.class);
        evidenceLog = mock(TaskEvidenceLog.class);

        executor = new DocumentTaskExecutor(
                documentRetriever, chunkRetriever, outlineGenerator,
                sectionGenerator, summaryGenerator, assembler, documentService, mock(ComparisonGenerator.class),
                taskRetrieval, evidenceLog, new DocumentTaskProperties());
        ReflectionTestUtils.setField(executor, "defaultTopK", 8);
        ReflectionTestUtils.setField(executor, "defaultThreshold", 0.5);
    }

    private DocumentTask runningTask() {
        DocumentTask task = DocumentTask.create(
                DocumentTaskType.REPORT, List.of("docA", "docB"), "生成一份设备维护报告");
        task.markRunning();
        return task;
    }

    private RetrievedChunk chunk(String chunkId, String docId, String fileName,
                                 Integer pageStart, Integer pageEnd, double score) {
        return new RetrievedChunk(chunkId, docId, fileName, "正文-" + chunkId,
                "章节路径", pageStart, pageEnd, 0, score);
    }

    private RetrievedDocument doc(String id, String fileName) {
        return new RetrievedDocument(id, fileName, 0.8, 1, List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_allRetrievalsScopedToSelectedDocuments_andUnselectedDocFiltered() {
        DocumentTask task = runningTask();

        // 文档级检索：返回命中 docA，并故意混入未选 docX（应被防御性过滤）；docB 未命中需占位
        when(documentRetriever.search(anyString(), any()))
                .thenReturn(List.of(doc("docA", "a.md"), doc("docX", "x.md")));
        when(documentService.getDocument("docB"))
                .thenReturn(new DocumentMetadata("docB", "b.md", "TXT", "docB",
                        1L, 2, "v2", DocumentStatus.READY.name(), null));

        ReportSectionPlan s1 = new ReportSectionPlan("第一章 概述", "介绍设备总体情况");
        ReportSectionPlan s2 = new ReportSectionPlan("第二章 维护周期", "说明周期与频次");
        when(outlineGenerator.generate(anyString(), eq(DocumentTaskType.REPORT), anyList()))
                .thenReturn(new ReportPlan("设备维护报告", List.of(s1, s2)));

        // 章节检索：第 1 章 cA1+cB1；第 2 章 cA1(重复)+cB2
        AtomicInteger chunkCall = new AtomicInteger();
        when(chunkRetriever.search(anyString(), any())).thenAnswer(invocation -> {
            int idx = chunkCall.getAndIncrement();
            if (idx == 0) {
                return List.of(chunk("cA1", "docA", "a.md", null, null, 0.91),
                        chunk("cB1", "docB", "b.md", null, null, 0.88));
            }
            return List.of(chunk("cA1", "docA", "a.md", null, null, 0.90),
                    chunk("cB2", "docB", "b.md", 3, 4, 0.80));
        });

        // 章节正文与证据均来自实际传入的检索切片
        when(sectionGenerator.generate(eq(DocumentTaskType.REPORT), any(), anyList(), eq(SELECTED)))
                .thenAnswer(invocation -> {
                    ReportSectionPlan plan = invocation.getArgument(1);
                    List<RetrievedChunk> chunks = invocation.getArgument(2);
                    List<Evidence> evidences = chunks.stream().map(Evidence::from).toList();
                    return new GeneratedSection(plan.title(), "正文-" + plan.title(), evidences);
                });
        when(summaryGenerator.generateSummary(anyString(), anyString(), anyList()))
                .thenReturn(new VerifiedSummary("这是整体摘要。", List.of(), List.of()));

        GeneratedDocument result = executor.execute(task);

        // 1. 文档级检索参数：topK / 阈值复用既有配置，documentIds 恰好是选中的两个
        ArgumentCaptor<RetrievalOptions> docOptionsCaptor = ArgumentCaptor.forClass(RetrievalOptions.class);
        verify(documentRetriever).search(eq("生成一份设备维护报告"), docOptionsCaptor.capture());
        RetrievalOptions docOptions = docOptionsCaptor.getValue();
        assertEquals(8, docOptions.topK());
        assertEquals(0.5, docOptions.threshold(), 1e-9);
        assertTrue(docOptions.hasDocumentFilter());
        assertEquals(SELECTED, docOptions.documentIds());

        // 2. 章节级检索执行 2 次，每次都携带完整 documentIds，且查询包含章节标题
        ArgumentCaptor<RetrievalOptions> chunkOptionsCaptor = ArgumentCaptor.forClass(RetrievalOptions.class);
        verify(chunkRetriever, times(2)).search(anyString(), chunkOptionsCaptor.capture());
        for (RetrievalOptions options : chunkOptionsCaptor.getAllValues()) {
            assertEquals(SELECTED, options.documentIds());
            assertTrue(options.hasDocumentFilter());
        }
        verify(chunkRetriever).search(contains("第一章 概述"), any());
        verify(chunkRetriever).search(contains("第二章 维护周期"), any());

        // 3. 大纲只看到 docA / docB，未选 docX 被过滤；未命中的 docB 走元数据占位
        ArgumentCaptor<List<RetrievedDocument>> outlineDocsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(outlineGenerator).generate(anyString(), eq(DocumentTaskType.REPORT),
                outlineDocsCaptor.capture());
        List<String> outlineDocIds = outlineDocsCaptor.getValue().stream()
                .map(RetrievedDocument::documentId).toList();
        assertEquals(2, outlineDocIds.size());
        assertTrue(outlineDocIds.contains("docA"));
        assertTrue(outlineDocIds.contains("docB"));
        assertTrue(!outlineDocIds.contains("docX"));
        verify(documentService).getDocument("docB");
        verify(documentService, never()).getDocument("docX");

        // 4. 章节顺序与大纲一致
        assertEquals(List.of("第一章 概述", "第二章 维护周期"),
                result.sections().stream().map(GeneratedSection::title).toList());

        // 5. 最终参考资料按文档去重：docA/docB 各保留首次出现的 cA1 / cB1，全部属于选中文档
        assertEquals(2, result.references().size());
        assertEquals(List.of("cA1", "cB1"),
                result.references().stream().map(Evidence::chunkId).toList());
        for (Evidence evidence : result.references()) {
            assertTrue(SELECTED.contains(evidence.documentId()));
        }
        // 章节级证据仍保留切片粒度：cB2 携带 3-4 页（页码不伪造），存在于第二章证据中
        Evidence cB2 = result.sections().stream()
                .flatMap(s -> s.evidences().stream())
                .filter(e -> e.chunkId().equals("cB2")).findFirst().orElseThrow();
        assertEquals(3, cB2.pageStart());
        assertEquals(4, cB2.pageEnd());
        Evidence cA1 = result.references().stream()
                .filter(e -> e.chunkId().equals("cA1")).findFirst().orElseThrow();
        assertEquals(null, cA1.pageStart());

        assertEquals("这是整体摘要。", result.summary());
        assertEquals("设备维护报告", result.title());
    }

    @Test
    void execute_sectionGenerationFails_propagatesException() {
        DocumentTask task = runningTask();
        when(documentRetriever.search(anyString(), any())).thenReturn(List.of(doc("docA", "a.md")));
        when(outlineGenerator.generate(anyString(), eq(DocumentTaskType.REPORT), anyList()))
                .thenReturn(new ReportPlan("标题",
                        List.of(new ReportSectionPlan("第一章", "目的"))));
        when(chunkRetriever.search(anyString(), any())).thenReturn(List.of());
        when(sectionGenerator.generate(any(), any(), anyList(), eq(SELECTED)))
                .thenThrow(new RuntimeException("LLM 服务不可用"));

        // 章节失败必须向上抛出（由 Service 转为整个任务 FAILED，不静默跳过）
        RuntimeException ex = assertThrows(RuntimeException.class, () -> executor.execute(task));
        assertTrue(ex.getMessage().contains("LLM 服务不可用"));
    }

    @Test
    void fullStrategyKeepsTheSuccessfulBatchWhenAnotherBatchTimesOut() {
        DocumentTask task = runningTask();
        var settings = new ReportRetrievalSettings("full", 8, 0.35, 40, 40);
        var plan = new ReportConfiguration("ANALYSIS", "1", "报告",
                List.of(new ReportConfiguration.Section("findings", "事实", true, "保留条件。")),
                "1. 数量\n2. 条件\n3. 金额\n4. 时间\n5. 范围",
                null, List.of(), null, null).withRetrieval(settings);
        task.setConfiguration(plan);
        when(taskRetrieval.hybrid(anyString(), eq(SELECTED), eq(settings)))
                .thenAnswer(call -> List.of(new RetrievedChunk(call.getArgument(0), "docA", "a.txt",
                        "示例", "", null, null, 0, 0.8)));
        var known = new Claim("C-1-1-1", "已处理", List.of(), SupportStatus.INSUFFICIENT, "测试", true);
        when(sectionGenerator.generateBatch(any(), any(), anyList(), eq(SELECTED), eq("C-1-1-"), eq(true)))
                .thenReturn(new GeneratedSection("事实", "已处理", List.of(), List.of(known)));
        when(sectionGenerator.generateBatch(any(), any(), anyList(), eq(SELECTED), eq("C-1-2-"), eq(true)))
                .thenThrow(new SectionGenerator.BatchFailure(SectionGenerator.BatchFailureKind.TIMEOUT, null));
        when(summaryGenerator.generateSummary(anyString(), anyString(), anyList()))
                .thenReturn(new VerifiedSummary("摘要", List.of(), List.of()));

        var section = executor.execute(task).sections().getFirst();
        assertEquals(List.of("C-1-1-1", "C-1-2-1"), section.claims().stream().map(Claim::claimId).toList());
        assertTrue(section.content().contains("已处理"));
        assertTrue(section.content().contains("范围（本批次生成失败）"));
        verify(taskRetrieval, times(5)).hybrid(anyString(), eq(SELECTED), eq(settings));
        verifyNoMoreInteractions(taskRetrieval);
        verifyNoInteractions(chunkRetriever);
    }

    @Test
    void anchoredStrategyGeneratesAndLogsTheActualSupplementedBatch() {
        DocumentTask task = runningTask();
        var settings = new ReportRetrievalSettings("full-v2", 8, 0.35, 120, 40);
        String question = "维护周期是多少？";
        task.setConfiguration(new ReportConfiguration("ANALYSIS", "1", "报告",
                List.of(new ReportConfiguration.Section("findings", "事实", true, "保留条件。")),
                "1. " + question, null, List.of(), null, null).withRetrieval(settings));
        var original = chunk("original", "docA", "a.pdf", 1, 1, 0.9);
        var added = chunk("added", "docA", "a.pdf", 2, 2, 0.0);
        var selected = List.of(original, added);
        var questions = List.of(new SectionGenerator.QuestionEvidence(1, question, List.of("original", "added")));
        var section = new GeneratedSection("事实", "已处理", List.of());
        when(taskRetrieval.hybrid(question, SELECTED, settings)).thenReturn(List.of(original));
        when(taskRetrieval.supplement(List.of(question), List.of(original), SELECTED, 120))
                .thenReturn(new TaskEvidenceRetrieval.Supplement(selected, 3, List.of(List.of("added"))));
        when(sectionGenerator.generateAnchoredBatch(any(), any(), eq(selected), eq(SELECTED), eq("C-1-1-"), eq(120),
                eq(questions), any()))
                .thenReturn(section);
        when(summaryGenerator.generateSummary(anyString(), anyString(), anyList()))
                .thenReturn(new VerifiedSummary("摘要", List.of(), List.of()));

        assertEquals("已处理", executor.execute(task).sections().getFirst().content());
        verify(sectionGenerator).generateAnchoredBatch(eq(DocumentTaskType.REPORT), any(), eq(selected),
                eq(SELECTED), eq("C-1-1-"), eq(120), eq(questions), any());
        verify(sectionGenerator, never()).generateBatch(any(), any(), any(), any(), any(), anyBoolean());
        verify(evidenceLog).event(eq(task.getTaskId()), eq(1), eq(1), eq("context"), eq("selected"),
                eq(4), eq(selected), anyLong());
    }

    @Test
    void anchoredStrategyPassesRankedRetrievalMembershipForEachQuestion() {
        DocumentTask task = runningTask();
        var settings = new ReportRetrievalSettings("full-v2", 8, 0.35, 120, 40);
        String firstQuestion = "维护周期是多少？";
        String secondQuestion = "谁负责巡检？";
        task.setConfiguration(new ReportConfiguration("ANALYSIS", "1", "报告",
                List.of(new ReportConfiguration.Section("findings", "事实", true, "保留条件。")),
                "1. " + firstQuestion + "\n2. " + secondQuestion, null, List.of(), null, null).withRetrieval(settings));
        var lead = chunk("lead", "docA", "a.pdf", 1, 1, 0.9);
        var inspector = chunk("inspector", "docA", "a.pdf", 2, 2, 0.9);
        var optional = chunk("optional", "docA", "a.pdf", 3, 3, 0.6);
        var selected = List.of(lead, inspector, optional);
        var questions = List.of(new SectionGenerator.QuestionEvidence(1, firstQuestion, List.of("lead", "optional")),
                new SectionGenerator.QuestionEvidence(2, secondQuestion, List.of("inspector", "lead")));
        var section = new GeneratedSection("事实", "已处理", List.of());
        when(taskRetrieval.hybrid(firstQuestion, SELECTED, settings)).thenReturn(List.of(lead, optional));
        when(taskRetrieval.hybrid(secondQuestion, SELECTED, settings)).thenReturn(List.of(inspector, lead));
        when(taskRetrieval.supplement(List.of(firstQuestion, secondQuestion), selected, SELECTED, 120))
                .thenReturn(new TaskEvidenceRetrieval.Supplement(selected, 0, List.of(List.of(), List.of())));
        when(sectionGenerator.generateAnchoredBatch(any(), any(), eq(selected), eq(SELECTED), eq("C-1-1-"), eq(120),
                eq(questions), any())).thenReturn(section);
        when(summaryGenerator.generateSummary(anyString(), anyString(), anyList()))
                .thenReturn(new VerifiedSummary("摘要", List.of(), List.of()));

        executor.execute(task);

        verify(sectionGenerator).generateAnchoredBatch(eq(DocumentTaskType.REPORT), any(), eq(selected), eq(SELECTED),
                eq("C-1-1-"), eq(120), eq(questions), any());
    }
}
