package com.wuyunbin.rag.service.comparison;

import com.wuyunbin.rag.dto.*;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkEntity;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
import com.wuyunbin.rag.retrieval.RetrievalPipeline;
import com.wuyunbin.rag.service.DocumentService;
import com.wuyunbin.rag.service.generation.*;
import com.wuyunbin.rag.service.generation.model.*;
import com.wuyunbin.rag.service.task.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import static com.wuyunbin.rag.service.comparison.ComparisonResult.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComparisonGeneratorTest {
    private final RetrievalPipeline retrieval = mock(RetrievalPipeline.class);
    private final ComparisonExtractor extractor = mock(ComparisonExtractor.class);
    private final DocumentChunkJpaRepository chunks = mock(DocumentChunkJpaRepository.class);
    private final ClaimSupportJudge support = mock(ClaimSupportJudge.class);
    private ComparisonGenerator generator;

    @BeforeEach void setup() {
        var properties = new DocumentTaskProperties();
        properties.setMaxContextChars(4000);
        generator = new ComparisonGenerator(retrieval, extractor, new ClaimVerifier(chunks, support),
                mock(DocumentService.class), new GeneratedDocumentAssembler(), properties);
        when(support.judge(anyString(), anyList())).thenReturn(
                new ClaimSupportJudge.Judgment(SupportStatus.SUPPORTED, "与完整原句一致"));
    }

    private DocumentTask task(List<String> documents, List<String> dimensions) {
        var request = new DocumentTaskRequest(DocumentTaskType.COMPARE, documents, "说明检修要求、各方原文",
                null, null, null, null, dimensions);
        var task = DocumentTask.create(request.type(), documents, request.instruction());
        task.setConfiguration(ReportTemplates.resolve(request));
        return task;
    }

    private RetrievedChunk hit(String document, String content) {
        var entity = new DocumentChunkEntity();
        entity.setChunkId("chunk-" + document);
        entity.setDocMd5(document);
        entity.setFileName(document);
        entity.setPipelineVersion("test");
        entity.setContent(content);
        when(chunks.findById(entity.getChunkId())).thenReturn(Optional.of(entity));
        return new RetrievedChunk(entity.getChunkId(), document, document, content, null, 1, 1, 0, 0.9);
    }

    @Test void independentBudgetsProduceCompleteMatrixAndEvidenceForBothSides() {
        var task = task(List.of("A", "B", "C"), List.of("检修", "周期"));
        String text = "甲泵必须断电后检修。";
        var a = hit("A", text);
        var b = hit("B", text);
        when(retrieval.search(anyString(), any())).thenAnswer(call -> {
            String dimension = call.getArgument(0);
            RetrievalOptions options = call.getArgument(1);
            assertEquals(1, options.documentIds().size());
            String id = options.documentIds().iterator().next();
            if (id.equals("C") && dimension.equals("周期")) throw new IllegalStateException("测试检索不可用");
            if (dimension.equals("周期") || id.equals("C")) return List.of();
            // Spring retrieval can return a stray hit. It must never reach the extractor or verifier.
            return id.equals("A") ? List.of(b, a) : List.of(a, b);
        });
        when(extractor.extract(eq("检修"), any(), anyList())).thenAnswer(call -> {
            List<RetrievedChunk> selected = call.getArgument(2);
            assertEquals(1, selected.size());
            return List.of(new ComparisonCandidate(1, text, "甲泵", "", "", "检修", "必须断电后检修", ""));
        });
        var result = generator.generate(task, 8, .5);
        var matrix = result.comparison();
        assertEquals(List.of("A","B","C"), matrix.documents().stream().map(Source::documentId).toList());
        assertEquals(List.of(Status.FOUND, Status.FOUND, Status.NOT_FOUND),
                matrix.rows().getFirst().cells().stream().map(Cell::status).toList());
        assertEquals(List.of(Status.NOT_FOUND, Status.NOT_FOUND, Status.RETRIEVAL_FAILED),
                matrix.rows().getLast().cells().stream().map(Cell::status).toList());
        assertEquals(6, matrix.metrics().retrievalCalls());
        assertEquals(4, matrix.metrics().modelCalls());
        var common = matrix.conclusions().getFirst();
        assertEquals(Kind.COMMON, common.kind());
        assertEquals(List.of("M-1-1-1", "M-1-2-1"), common.claimIds());
        assertEquals(common.claimIds(), result.summaryClaimIds());
        assertEquals(List.of("A","B"), result.evidenceSnapshots().stream().map(Evidence::documentId).toList());
        new EvidenceIntegrityValidator().validate(result, Set.of("A","B","C"));
        verify(retrieval, times(6)).search(anyString(), any());
        verify(extractor, times(2)).extract(anyString(), any(), anyList());
    }

    @Test void twoDocumentExtractionFailureAndUnverifiedQuoteRemainDistinctFromNoEvidence() {
        var task = task(List.of("A", "B"), List.of("设备"));
        var a = hit("A", "甲泵属于设备。");
        var b = hit("B", "乙泵属于设备。");
        when(retrieval.search(anyString(), any())).thenAnswer(call ->
                ((RetrievalOptions) call.getArgument(1)).documentIds().contains("A") ? List.of(a) : List.of(b));
        when(extractor.extract(anyString(), any(), eq(List.of(a)))).thenThrow(new IllegalStateException("测试提取失败"));
        when(extractor.extract(anyString(), any(), eq(List.of(b)))).thenReturn(
                List.of(new ComparisonCandidate(1, "篡改引文", "乙泵", "", "", "设备", "设备", "")));
        var result = generator.generate(task, 8, .5);
        var matrix = result.comparison();
        assertEquals(List.of(Status.EXTRACTION_FAILED, Status.UNVERIFIED),
                matrix.rows().getFirst().cells().stream().map(Cell::status).toList());
        assertEquals(2, matrix.metrics().modelCalls());
        assertTrue(matrix.conclusions().isEmpty());
        assertEquals(SourceStatus.QUOTE_MISMATCH, result.evidenceSnapshots().getFirst().sourceStatus());
    }

    @Test void costBudgetRejectsDuplicateDocumentsAndLargeCrossProductsBeforeRetrieval() {
        assertThrows(IllegalArgumentException.class, () -> task(List.of("A", "A"), List.of("周期")));
        assertThrows(IllegalArgumentException.class, () -> task(List.of("A"), List.of("周期")));
        assertThrows(IllegalArgumentException.class, () -> task(List.of("A", "B", "C", "D", "E"),
                List.of("一","二","三","四","五")));
        verifyNoInteractions(retrieval);
    }

    @Test void matchesOnlyFactsSelectedUnderTheUserAndSectionInstructions() {
        String selectedQuote = "Z82 设备必须按每 4 周一次的固定周期校准。";
        String excludedQuote = "Z83 设备必须按每 5 周一次的固定周期校准。";
        var request = new DocumentTaskRequest(DocumentTaskType.COMPARE, List.of("A", "B"), "仅对比 Z82 设备",
                "COMPARISON", "1", List.of(new DocumentTaskRequest.SectionSelection("matrix", "排除 Z83 设备")),
                null, List.of("固定校准周期"));
        var task = DocumentTask.create(request.type(), request.documentIds(), request.instruction());
        var configuration = ReportTemplates.resolve(request);
        task.setConfiguration(configuration);
        var a = hit("A", selectedQuote + excludedQuote);
        var b = hit("B", excludedQuote);
        when(retrieval.search(anyString(), any())).thenAnswer(call ->
                ((RetrievalOptions) call.getArgument(1)).documentIds().contains("A") ? List.of(a) : List.of(b));
        when(extractor.extract(eq("固定校准周期"), same(configuration), eq(List.of(a)))).thenReturn(
                List.of(new ComparisonCandidate(1, selectedQuote, "Z82", "", "", "校准", "4", "周")));
        when(extractor.extract(eq("固定校准周期"), same(configuration), eq(List.of(b)))).thenReturn(List.of());

        var report = generator.generate(task, 8, .5);
        var cells = report.comparison().rows().getFirst().cells();
        assertEquals(List.of(Status.FOUND, Status.NOT_FOUND), cells.stream().map(Cell::status).toList());
        assertEquals(List.of(selectedQuote), report.evidenceSnapshots().stream().map(Evidence::quote).toList());
        assertTrue(configuration.sections().getFirst().instruction().contains("排除 Z83 设备"));
        verify(extractor, times(2)).extract(eq("固定校准周期"), same(configuration), anyList());
    }

    @Test void retrievesOnlyRelevantAtomicEvidenceAndAlignsFieldsIndependentOfWording() {
        var task = task(List.of("A", "B", "C"), List.of("固定校准周期", "检修断电", "年度设备预算"));
        String firstPower = "检修 Z82 设备前必须断电。";
        String secondPower = "检修 Z82 设备前须切断电源。";
        String firstCycle = "常规环境下，Z82 设备必须按每 4 周一次的固定周期校准。";
        String secondCycle = "设备 Z82 在常规环境的固定校验周期为 35 天。";
        String firstBudget = "临海项目 2027 年度设备预算为 0.5 万元。";
        String secondBudget = "临海项目 2027 年设备预算为 5,000 元。";
        String spending = "临海项目 2027 年上半年设备实际支出为 0.3 万元。";
        var documents = Map.of(
                "A", hit("A", "适用于本项目。" + firstPower + firstCycle + firstBudget),
                "B", hit("B", secondPower + secondCycle + secondBudget + spending),
                "C", hit("C", "临海项目的设备范围包括 Z82。"));
        var selectedQuotes = Map.of(
                "固定校准周期", Map.of("A", List.of(firstCycle), "B", List.of(secondCycle)),
                "检修断电", Map.of("A", List.of(firstPower), "B", List.of(secondPower)),
                "年度设备预算", Map.of("A", List.of(firstBudget), "B", List.of(secondBudget, spending)));
        when(retrieval.search(anyString(), any())).thenAnswer(call -> List.of(documents.get(
                ((RetrievalOptions) call.getArgument(1)).documentIds().iterator().next())));
        when(extractor.extract(anyString(), any(), anyList())).thenAnswer(call -> {
            String dimension = call.getArgument(0);
            List<RetrievedChunk> selected = call.getArgument(2);
            return selectedQuotes.get(dimension).getOrDefault(selected.getFirst().documentId(), List.of()).stream()
                    .map(quote -> new ComparisonCandidate(1, quote, "", "", "", "", "", ""))
                    .toList();
        });
        var report = generator.generate(task, 8, .5);
        var matrix = report.comparison();
        assertEquals(List.of(1, 1, 0, 1, 1, 0, 1, 2, 0), matrix.rows().stream()
                .flatMap(row -> row.cells().stream()).map(cell -> cell.facts().size()).toList());
        for (var row : matrix.rows()) assertEquals(Status.NOT_FOUND, row.cells().getLast().status());
        var pairs = matrix.conclusions().stream().filter(c -> c.claimIds().size() == 2).toList();
        assertEquals(List.of(Kind.POSSIBLE_CONFLICT, Kind.COMMON, Kind.COMMON, Kind.INCOMPARABLE),
                pairs.stream().map(Conclusion::kind).toList());
        assertTrue(pairs.getFirst().text().contains("常规环境"));
        assertEquals("5000", matrix.rows().getLast().cells().getFirst().facts().getFirst().normalizedValue());
        new EvidenceIntegrityValidator().validate(report, documents.keySet());
    }
    @Test void queryAndModelCostScaleWithCellsAndOnlyCountMatchedCitations() {
        String quote = "甲泵必须断电后检修。";
        Map<String,RetrievedChunk> corpus = Map.of("A", hit("A", quote), "B", hit("B", quote), "C", hit("C", quote));
        when(retrieval.search(anyString(), any())).thenAnswer(call -> {
            RetrievalOptions options = call.getArgument(1);
            return List.of(corpus.get(options.documentIds().iterator().next()));
        });
        when(extractor.extract(anyString(), any(), anyList())).thenReturn(
                List.of(new ComparisonCandidate(1, quote, "甲泵", "", "", "检修", "必须断电后检修", "")));
        var base = generator.generate(task(List.of("A","B"), List.of("检修")), 8, .5).comparison().metrics();
        var extraDocument = generator.generate(task(List.of("A","B","C"), List.of("检修")), 8, .5).comparison().metrics();
        var extraDimension = generator.generate(task(List.of("A","B"), List.of("检修","安全")), 8, .5).comparison().metrics();
        assertEquals(1, extraDocument.retrievalCalls() - base.retrievalCalls());
        assertEquals(2, extraDocument.modelCalls() - base.modelCalls());
        assertEquals(2, extraDimension.retrievalCalls() - base.retrievalCalls());
        assertEquals(4, extraDimension.modelCalls() - base.modelCalls());
    }
}
