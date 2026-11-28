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
    private final TaskEvidenceRetrieval taskRetrieval = mock(TaskEvidenceRetrieval.class);
    private final ClaimVerifier verifier = spy(new ClaimVerifier(chunks, support));
    private ComparisonGenerator generator;

    @BeforeEach void setup() {
        var properties = new DocumentTaskProperties();
        properties.setMaxContextChars(4000);
        generator = new ComparisonGenerator(retrieval, extractor, verifier,
                mock(DocumentService.class), new GeneratedDocumentAssembler(), properties,
                taskRetrieval, mock(TaskEvidenceLog.class));
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

    @Test void anchoredGenerationSeparatesDimensionEvidence() {
        var task = task(List.of("A", "B"), List.of("固定巡检周期", "校准前断电"));
        task.setConfiguration(task.getConfiguration().withRetrieval(new ReportRetrievalSettings("full-v2", 8, .5, 4000, 4000)));
        String cycleA = "在普通车间内，K21 压缩机的固定巡检周期为 3 周。";
        String cycleB = "在普通车间内，K21 压缩机的固定巡检周期为 25 天。";
        String power = "校准 K21 压缩机前必须切断电源。";
        var documents = Map.of("A", hit("A", cycleA + power), "B", hit("B", cycleB + power));
        when(taskRetrieval.hybrid(anyString(), anySet(), any())).thenAnswer(call ->
                List.of(documents.get(((Set<?>) call.getArgument(1)).iterator().next())));
        when(verifier.evidenceOptions(anyList(), anyString(), anyInt())).thenAnswer(call -> {
            List<RetrievedChunk> selected = call.getArgument(0);
            String cycle = selected.getFirst().documentId().equals("A") ? cycleA : cycleB;
            return new ClaimVerifier.EvidenceOptions("", Map.of("S1E1", new ClaimVerifier.Citation(1, cycle),
                    "S1E2", new ClaimVerifier.Citation(1, power)));
        });
        when(support.judgeInScope(anyString(), anyList(), anyString())).thenReturn(
                new ClaimSupportJudge.Judgment(SupportStatus.SUPPORTED, "与当前维度的完整原句一致"));

        var matrix = generator.generate(task, 8, .5).comparison();

        assertEquals(List.of(Kind.POSSIBLE_CONFLICT, Kind.COMMON), matrix.conclusions().stream()
                .filter(conclusion -> conclusion.claimIds().size() == 2).map(Conclusion::kind).toList());
        assertEquals(List.of(cycleA, cycleB, power, power), matrix.rows().stream()
                .flatMap(row -> row.cells().stream()).flatMap(cell -> cell.facts().stream())
                .filter(Fact::verified).map(Fact::quote).toList());
        verifyNoInteractions(extractor);
    }

    @Test void anchoredFixedIntervalsIgnorePageHeadersWhilePreservingQuotes() {
        var task = anchoredTask("对照 C7 压缩机在干燥车间的固定巡检周期。", "固定巡检周期");
        String cycleA = "续页　北岸设备维护规程甲\n\n二、巡检要求\n\n在干燥车间内，C7 压缩机的固定巡检周期为 14 天。";
        String cycleB = "在干燥车间内，C7 压缩机的固定巡检周期为 28 天。";
        anchoredDocuments(Map.of("A", List.of(cycleA), "B", List.of(cycleB)));

        var report = generator.generate(task, 8, .5);
        var comparison = report.comparison();
        var leftFact = comparison.rows().getFirst().cells().getFirst().facts().getFirst();
        assertEquals("干燥车间", leftFact.condition());
        assertEquals(cycleA, leftFact.quote());
        var pair = comparison.conclusions().stream().filter(conclusion -> conclusion.claimIds().size() == 2)
                .findFirst().orElseThrow();
        assertEquals(Kind.POSSIBLE_CONFLICT, pair.kind());
        assertEquals(ReasonCode.FIXED_VALUE_CONFLICT, pair.reasonCode());
        assertTrue(pair.text().contains(cycleA) && pair.text().contains(cycleB));
        new EvidenceIntegrityValidator().validate(report, Set.of("A", "B"));
    }

    @Test void anchoredScopeReportsExcludedObjectFromTheMatchingOriginal() {
        var task = anchoredTask("只核对北库常温区的货梯检修周期。", "固定检修周期",
                new DocumentTaskRequest.SectionSelection("matrix", "只纳入 Z8 货梯。明确排除 Z9 货梯。"));
        anchoredDocuments(Map.of("A", List.of("在北库常温区，Z8 货梯的固定检修周期为 15 天。"), "B", List.of(
                "在北库常温区，Z9 货梯的固定检修周期为 7 天。",
                "Z8 货梯由设施组协调。", "本记录没有其他货梯的检修周期。")));

        var comparison = generator.generate(task, 8, .5).comparison();
        var cells = comparison.rows().getFirst().cells();

        assertEquals(List.of(Status.FOUND, Status.NOT_FOUND), cells.stream().map(Cell::status).toList());
        var excluded = cells.getLast();
        assertEquals(ReasonCode.OUTSIDE_SCOPE, excluded.reasonCode());
        assertTrue(excluded.reason().contains("明确排除的「Z9 货梯」") && excluded.reason().contains("仅纳入 Z8"),
                excluded.reason());
        assertTrue(excluded.facts().isEmpty());
        assertTrue(comparison.conclusions().stream().noneMatch(conclusion -> conclusion.claimIds().size() > 1));
        verifyNoInteractions(extractor);
    }

    @Test void anchoredEmptyModelExtractionDoesNotEstablishAbsence() {
        var task = anchoredTask("对照故障到场时限。", "故障到场时限");
        anchoredDocuments(Map.of("A", List.of("北湖园区承诺故障到场时限至多 6 小时。"),
                "B", List.of("本段仅记录替换备件保修期为 18 个月。")));
        when(extractor.extractAnchored(any(), any())).thenReturn(
                new ComparisonExtractor.AtomicExtraction(List.of(), List.of()));

        var comparison = generator.generate(task, 8, .5).comparison();
        var cells = comparison.rows().getFirst().cells();

        assertEquals(List.of(Status.UNVERIFIED, Status.NOT_FOUND), cells.stream().map(Cell::status).toList());
        assertEquals(ReasonCode.INSUFFICIENT_SUPPORT, cells.getFirst().reasonCode());
        assertEquals(ReasonCode.NO_MATCHING_FACT, cells.getLast().reasonCode());
        assertTrue(comparison.conclusions().isEmpty());
    }

    @Test void anchoredMetricWithAnUnparsedQuantityRemainsUnverified() {
        var task = anchoredTask("对照故障到场时限。", "故障到场时限");
        anchoredDocuments(Map.of("A", List.of("A12 设备的故障到场时限为 500 毫秒。"),
                "B", List.of("B12 设备的故障到场时限为十五天。")));
        when(extractor.extractAnchored(any(), any())).thenReturn(
                new ComparisonExtractor.AtomicExtraction(List.of(), List.of()));

        var cells = generator.generate(task, 8, .5).comparison().rows().getFirst().cells();
        assertEquals(List.of(Status.UNVERIFIED, Status.UNVERIFIED), cells.stream().map(Cell::status).toList());
        assertTrue(cells.stream().allMatch(cell -> cell.reasonCode() == ReasonCode.INSUFFICIENT_SUPPORT));
    }

    @Test void anchoredScopeWithoutAnIdentifiableObjectRemainsUnverified() {
        var task = anchoredTask("只纳入 Z8 货梯。", "固定检修周期");
        anchoredDocuments(Map.of("A", List.of("在北库常温区，货梯的固定检修周期为 15 天。"),
                "B", List.of("在北库常温区，Z8 货梯的固定检修周期为 15 天。")));

        var cells = generator.generate(task, 8, .5).comparison().rows().getFirst().cells();

        assertEquals(List.of(Status.UNVERIFIED, Status.FOUND), cells.stream().map(Cell::status).toList());
        assertEquals(ReasonCode.INSUFFICIENT_SUPPORT, cells.getFirst().reasonCode());
    }

    @Test void anchoredContextWithoutOriginalAnchorsRemainsUnverified() {
        var task = anchoredTask("对照故障到场时限。", "故障到场时限");
        var unlocated = hit("A", "在工作日，北湖园区的故障到场时限为 6 小时。");
        when(taskRetrieval.hybrid(anyString(), eq(Set.of("A")), any())).thenReturn(List.of(unlocated));
        when(taskRetrieval.hybrid(anyString(), eq(Set.of("B")), any())).thenReturn(List.of());

        var cells = generator.generate(task, 8, .5).comparison().rows().getFirst().cells();

        assertEquals(List.of(Status.UNVERIFIED, Status.NOT_FOUND), cells.stream().map(Cell::status).toList());
        assertEquals(ReasonCode.NO_ORIGINAL_EVIDENCE, cells.getFirst().reasonCode());
        verifyNoInteractions(extractor);
    }

    private DocumentTask anchoredTask(String instruction, String dimension,
                                      DocumentTaskRequest.SectionSelection... sections) {
        List<String> documents = List.of("A", "B");
        var request = new DocumentTaskRequest(DocumentTaskType.COMPARE, documents, instruction,
                "COMPARISON", "1", sections.length == 0 ? null : List.of(sections), null, List.of(dimension));
        var task = DocumentTask.create(request.type(), documents, request.instruction());
        task.setConfiguration(ReportTemplates.resolve(request)
                .withRetrieval(new ReportRetrievalSettings("full-v2", 8, .5, 4000, 4000)));
        return task;
    }

    private void anchoredDocuments(Map<String, List<String>> quotesByDocument) {
        Map<String, RetrievedChunk> documents = new HashMap<>();
        quotesByDocument.forEach((document, quotes) -> documents.put(document, hit(document, String.join("\n", quotes))));
        when(taskRetrieval.hybrid(anyString(), anySet(), any())).thenAnswer(call ->
                List.of(documents.get(((Set<?>) call.getArgument(1)).iterator().next())));
        when(verifier.evidenceOptions(anyList(), anyString(), anyInt())).thenAnswer(call -> {
            List<RetrievedChunk> selected = call.getArgument(0);
            Map<String, ClaimVerifier.Citation> citations = new LinkedHashMap<>();
            for (String quote : quotesByDocument.get(selected.getFirst().documentId())) {
                citations.put("S1E" + (citations.size() + 1), new ClaimVerifier.Citation(1, quote));
            }
            return new ClaimVerifier.EvidenceOptions("", citations);
        });
        when(support.judgeInScope(anyString(), anyList(), anyString())).thenReturn(
                new ClaimSupportJudge.Judgment(SupportStatus.SUPPORTED, "与当前维度的完整原句一致"));
    }

    @Test void anchoredComparisonRetainsOnlyTheQuotedPeriodAndConditions() {
        var task = task(List.of("A", "B"), List.of("年度采购预算", "固定交付周期", "故障到场时限", "备件保修期"));
        var configuration = task.getConfiguration();
        configuration = new ReportConfiguration(configuration.templateId(), configuration.templateVersion(), configuration.title(),
                configuration.sections(), "核对枫林园区 2028 年采购预算、库存足够时的交付周期、故障到场时限和备件保修期。",
                configuration.exportOptions(), configuration.comparisonDimensions(), configuration.model(), configuration.promptVersion(),
                new ReportRetrievalSettings("full-v2", 8, .5, 4000, 4000));
        task.setConfiguration(configuration);
        List<String> a = List.of("枫林园区 2028 年度采购预算为 6.2 万元。", "在库存足够时，枫林园区的固定交付周期为 4 周。",
                "在工作日，枫林园区的故障到场时限为 7 小时。", "枫林园区的替换备件保修期为 24 个月。");
        String budgetRow = "| 枫林园区 2028 年度采购预算为 62,000 元。 | 建议由值班人员复核库存 |";
        List<String> b = List.of(budgetRow, "在库存足够时，枫林园区的固定交付周期为 28 天。",
                "在法定节假日，枫林园区的故障到场时限为 8 小时。", "枫林园区的替换备件保修期为 2 年。");
        var documents = Map.of("A", hit("A", String.join("", a)), "B", hit("B", String.join("", b)));
        when(taskRetrieval.hybrid(anyString(), anySet(), any())).thenAnswer(call ->
                List.of(documents.get(((Set<?>) call.getArgument(1)).iterator().next())));
        when(verifier.evidenceOptions(anyList(), anyString(), anyInt())).thenAnswer(call -> {
            List<RetrievedChunk> selected = call.getArgument(0);
            List<String> quotes = selected.getFirst().documentId().equals("A") ? a : b;
            Map<String, ClaimVerifier.Citation> citations = new LinkedHashMap<>();
            for (String quote : quotes) {
                var citation = quote.equals(budgetRow) ? new ClaimVerifier.Citation(1, quote, "预算!A2:B2", quote)
                        : new ClaimVerifier.Citation(1, quote);
                citations.put("S1E" + (citations.size() + 1), citation);
            }
            return new ClaimVerifier.EvidenceOptions("", citations);
        });
        when(support.judgeInScope(anyString(), anyList(), anyString())).thenAnswer(call -> {
            String scope = call.getArgument(2);
            if (scope.startsWith("固定交付周期")) assertFalse(scope.contains("2028") || scope.contains("采购预算"), scope);
            if (scope.startsWith("备件保修期")) assertFalse(scope.contains("库存") || scope.contains("到场"), scope);
            return new ClaimSupportJudge.Judgment(SupportStatus.SUPPORTED, "与当前维度的完整原句一致");
        });

        var matrix = generator.generate(task, 8, .5).comparison();

        assertEquals(List.of(Kind.COMMON, Kind.COMMON, Kind.INCOMPARABLE, Kind.COMMON), matrix.conclusions().stream()
                .filter(conclusion -> conclusion.claimIds().size() == 2).map(Conclusion::kind).toList());
        var sla = matrix.conclusions().stream().filter(conclusion -> conclusion.kind() == Kind.INCOMPARABLE).findFirst().orElseThrow();
        assertEquals(ReasonCode.CONDITION_MISMATCH, sla.reasonCode());
        assertTrue(sla.text().contains("工作日 / 法定节假日"), sla.text());
        assertEquals(budgetRow, matrix.rows().getFirst().cells().getLast().facts().getFirst().quote());
        assertEquals("", matrix.rows().get(1).cells().getFirst().facts().getFirst().period());
        assertEquals("", matrix.rows().getLast().cells().getFirst().facts().getFirst().condition());
        assertEquals(0, matrix.metrics().modelCalls());
        verify(taskRetrieval, times(2)).hybrid(argThat(query -> query.startsWith("固定交付周期")
                && query.contains("库存足够") && query.contains("枫林园区") && !query.contains("2028")), anySet(), any());
    }

    @Test void anchoredScopeKeepsSeparatelyNamedSubjectsWithTheirDimensions() {
        var request = new DocumentTaskRequest(DocumentTaskType.COMPARE, List.of("A", "B"),
                "核对和平园区的年度采购预算和湖畔园区在库存足够时的交付周期。仅纳入甲区和乙区的数据。",
                "COMPARISON", "1", null, null, List.of("年度采购预算", "固定交付周期"));
        var config = ReportTemplates.resolve(request);

        String budget = ComparisonCellScope.from("年度采购预算", config).constraints();
        String delivery = ComparisonCellScope.from("固定交付周期", config).constraints();

        assertTrue(budget.contains("和平园区") && !budget.contains("湖畔园区"));
        assertTrue(delivery.contains("湖畔园区") && !delivery.contains("和平园区"));
        assertTrue(delivery.contains("仅纳入甲区和乙区的数据"));
    }

    @Test void anchoredFallbackRejectsOtherDimensionsAndInventedOriginalFields() {
        var task = task(List.of("A", "B"), List.of("故障到场时限"));
        task.setConfiguration(task.getConfiguration().withRetrieval(new ReportRetrievalSettings("full-v2", 8, .5, 4000, 4000)));
        String otherMetric = "未约定故障到场时限，替换备件保修期为 18 个月。";
        String noYear = "北湖园区承诺故障到场时限至多 6 小时。";
        var documents = Map.of("A", hit("A", otherMetric), "B", hit("B", noYear));
        when(taskRetrieval.hybrid(anyString(), anySet(), any())).thenAnswer(call ->
                List.of(documents.get(((Set<?>) call.getArgument(1)).iterator().next())));
        when(verifier.evidenceOptions(anyList(), anyString(), anyInt())).thenAnswer(call -> {
            List<RetrievedChunk> selected = call.getArgument(0);
            String quote = selected.getFirst().content();
            return new ClaimVerifier.EvidenceOptions(quote, Map.of("S1E1", new ClaimVerifier.Citation(1, quote)));
        });
        when(extractor.extractAnchored(any(), any())).thenAnswer(call -> {
            ClaimVerifier.EvidenceOptions evidence = call.getArgument(1);
            assertEquals(Set.of("S1E1"), evidence.citations().keySet());
            boolean crossDimension = evidence.citations().get("S1E1").quote().equals(otherMetric);
            var fact = crossDimension
                    ? new ComparisonExtractor.NumericFact("S1E1", "", "", "", "故障到场时限", "18", "个月")
                    : new ComparisonExtractor.NumericFact("S1E1", "北湖园区", "2028年", "", "故障到场时限", "6", "小时");
            var unknown = new ComparisonExtractor.NumericFact("S8E72", "", "", "", "故障到场时限", "6", "小时");
            return new ComparisonExtractor.AtomicExtraction(List.of(fact, unknown), List.of());
        });

        var comparison = generator.generate(task, 8, .5).comparison();

        assertTrue(comparison.conclusions().isEmpty());
        assertEquals(List.of(Status.UNVERIFIED, Status.UNVERIFIED),
                comparison.rows().getFirst().cells().stream().map(Cell::status).toList());
        assertTrue(comparison.rows().getFirst().cells().stream()
                .allMatch(cell -> cell.reasonCode() == ReasonCode.INVALID_FIELD_OR_ANCHOR && cell.facts().isEmpty()));
        verify(support, never()).judgeInScope(anyString(), anyList(), anyString());
    }

    @Test void anchoredFallbackKeepsAnAdditionalFactWhenAnExactFactAlreadyExists() {
        var task = task(List.of("A", "B"), List.of("故障到场时限"));
        task.setConfiguration(task.getConfiguration().withRetrieval(new ReportRetrievalSettings("full-v2", 8, .5, 4000, 4000)));
        String literal = "在常规工况下，A12 设备的故障到场时限为 6 小时。";
        String irregular = "A12 设备承诺故障到场时限至多 8 小时。";
        String neighboringLine = "故障到场时限调整为 12 小时。";
        var documents = Map.of("A", hit("A", literal + irregular), "B", hit("B", literal));
        when(taskRetrieval.hybrid(anyString(), anySet(), any())).thenAnswer(call ->
                List.of(documents.get(((Set<?>) call.getArgument(1)).iterator().next())));
        when(verifier.evidenceOptions(anyList(), anyString(), anyInt())).thenAnswer(call -> {
            List<RetrievedChunk> selected = call.getArgument(0);
            Map<String, ClaimVerifier.Citation> anchors = new LinkedHashMap<>();
            anchors.put("S1E1", new ClaimVerifier.Citation(1, literal));
            if (selected.getFirst().documentId().equals("A")) {
                anchors.put("S1E2", new ClaimVerifier.Citation(1, irregular, null, irregular + "\n" + neighboringLine));
            }
            return new ClaimVerifier.EvidenceOptions("", anchors);
        });
        when(extractor.extractAnchored(any(), any())).thenAnswer(call -> {
            ClaimVerifier.EvidenceOptions evidence = call.getArgument(1);
            assertEquals(Set.of("S1E2"), evidence.citations().keySet());
            assertEquals("[S1E2] " + irregular + "\n", evidence.prompt());
            return new ComparisonExtractor.AtomicExtraction(List.of(
                    new ComparisonExtractor.NumericFact("S1E2", "A12 设备", "", "", "故障到场时限", "8", "小时")), List.of());
        });
        when(support.judgeInScope(anyString(), anyList(), anyString())).thenReturn(
                new ClaimSupportJudge.Judgment(SupportStatus.SUPPORTED, "与当前维度的完整原句一致"));

        var row = generator.generate(task, 8, .5).comparison().rows().getFirst();

        assertEquals(List.of(literal, irregular), row.cells().getFirst().facts().stream().filter(Fact::verified)
                .map(Fact::quote).toList());
        verify(extractor).extractAnchored(any(), any());
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
