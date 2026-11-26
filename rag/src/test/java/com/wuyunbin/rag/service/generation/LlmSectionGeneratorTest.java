package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.dto.DocumentTaskType;
import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkEntity;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
import com.wuyunbin.rag.service.generation.model.Claim;
import com.wuyunbin.rag.service.generation.model.ReportSectionPlan;
import com.wuyunbin.rag.service.generation.model.SupportStatus;
import com.wuyunbin.rag.service.task.DocumentTaskProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LlmSectionGeneratorTest {
    private static final String SOURCE_TEXT = "该设备已经通过审核。";
    private static final ReportSectionPlan PLAN = new ReportSectionPlan("逐项核对", "1. 是否通过审核\n2. 谁确认");
    private static final RetrievedChunk SOURCE = new RetrievedChunk(
            "chunk-1", "doc-1", "report.pdf", SOURCE_TEXT, "审核", 4, 4, 0, 0.9);
    private static final String SUPPORTED_RESPONSE = """
            {"claims":[{"question":1,"text":"该设备已经通过审核。","citations":[{"source":1,"quote":"该设备已经通过审核。"}]}],"unattributedText":""}
            """;

    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
    private LlmSectionGenerator generator;

    @BeforeEach
    void setUp() {
        var request = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.call()).thenReturn(response);

        var chunks = mock(DocumentChunkJpaRepository.class);
        var stored = new DocumentChunkEntity();
        stored.setChunkId("chunk-1");
        stored.setDocMd5("doc-1");
        stored.setContent(SOURCE_TEXT);
        stored.setFileName("report.pdf");
        stored.setPageStart(4);
        stored.setPageEnd(4);
        when(chunks.findById("chunk-1")).thenReturn(Optional.of(stored));
        var judge = mock(ClaimSupportJudge.class);
        when(judge.judge(anyString(), anyList()))
                .thenReturn(new ClaimSupportJudge.Judgment(SupportStatus.SUPPORTED, "引文直接支持。"));
        var properties = new DocumentTaskProperties();
        properties.setMaxContextChars(1);
        generator = new LlmSectionGenerator(chatClient, new PromptTemplates(new DefaultResourceLoader()),
                properties, new ClaimVerifier(chunks, judge));
    }

    @Test void explicitTableReferencesAreBoundToTheNumberedQuestion() {
        var first = new ClaimVerifier.Citation(1, "A 11", null, "caption", List.of("甲为11"), "表1 原始指标");
        var second = new ClaimVerifier.Citation(1, "A 12", null, "caption", List.of("甲为12"), "表12 其他指标");
        var noCaption = new ClaimVerifier.Citation(1, "A 13");
        var scope = new TableQuestionScope("1. 按表1回答。\n2. 按表12回答。\n3. 给出正文说明。");
        assertTrue(scope.allows(1, first));
        assertFalse(scope.allows(1, second));
        assertFalse(scope.allows(1, noCaption));
        assertTrue(scope.allows(2, second));
        assertTrue(scope.allows(3, noCaption));
        assertFalse(scope.allows(4, first));
        assertFalse(scope.allows(null, second));
    }
    @Test void anchoredBatchResolvesOnlyAnOfferedIdAndDisregardsModelTranscription() throws Exception {
        var storage = mock(com.wuyunbin.rag.service.storage.DocumentStorage.class);
        byte[] bytes = SOURCE_TEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = org.springframework.util.DigestUtils.md5DigestAsHex(bytes);
        when(storage.load(digest)).thenReturn(Optional.of(new com.wuyunbin.rag.service.storage.DocumentStorage.StoredDocument(
                digest,"source.txt",bytes)));
        var chunks = mock(DocumentChunkJpaRepository.class);
        var row = new DocumentChunkEntity();
        row.setDocMd5(digest);
        row.setContent(SOURCE_TEXT);
        row.setFileName("source.txt");
        when(chunks.findById("c")).thenReturn(Optional.of(row));
        var judge = mock(ClaimSupportJudge.class);
        when(judge.judge(anyString(), anyList())).thenReturn(new ClaimSupportJudge.Judgment(SupportStatus.SUPPORTED,"原文支持。"));
        var generator = new LlmSectionGenerator(chatClient, new PromptTemplates(new DefaultResourceLoader()),
                new DocumentTaskProperties(), new ClaimVerifier(chunks, judge, new OriginalPassages(storage,null,null,null)));
        when(response.content()).thenReturn("""
                {"claims":[{"question":1,"text":"该设备已经通过审核。","evidenceIds":["S1E1"],"citations":[{"source":1,"quote":"模型伪造"}]},
                           {"question":2,"text":"负责人已签字。","evidenceIds":["S9E9"]}]}
                """);
        var shown = new ArrayList<ClaimVerifier.AnchorSpan>();
        var result = generator.generateAnchoredBatch(DocumentTaskType.REPORT,PLAN,
                List.of(new RetrievedChunk("c",digest,"source.txt",SOURCE_TEXT,null,null,null,0,1)),Set.of(digest),"batch",
                120, shown::addAll);
        assertEquals(List.of(new ClaimVerifier.AnchorSpan("c", digest, null, null, 0, SOURCE_TEXT.length(), 1),
                new ClaimVerifier.AnchorSpan("c", digest, null, null, 0, SOURCE_TEXT.length(), 2)), shown);
        assertEquals(List.of(SupportStatus.SUPPORTED,SupportStatus.INSUFFICIENT),result.claims().stream().map(Claim::supportStatus).toList());
        assertEquals(SOURCE_TEXT,result.evidences().getFirst().quote());
        assertTrue(result.evidences().getFirst().nativeLocation().startsWith("原文字符"));
    }

    @Test void tableAnswerUsesAnOfferedFactNumberInsteadOfModelTranscription() {
        var verifier = mock(ClaimVerifier.class);
        String proven = "甲工厂的质检得分为17.5，排名第2";
        var citation = new ClaimVerifier.Citation(1, SOURCE_TEXT, null, "数据表", List.of(proven), "表3 数据表");
        when(verifier.evidenceOptions(anyList(), anyString(), anyInt()))
                .thenReturn(new ClaimVerifier.EvidenceOptions("[S1E1]", Map.of("S1E1", citation)));
        when(verifier.verifyStrict(anyString(), anyString(), anyList(), anyList(), anySet())).thenAnswer(call ->
                new ClaimVerifier.VerifiedClaim(Claim.unsupported(call.getArgument(0), call.getArgument(1), "测试"), List.of()));
        var anchored = new LlmSectionGenerator(chatClient, new PromptTemplates(new DefaultResourceLoader()),
                new DocumentTaskProperties(), verifier);
        when(response.content()).thenReturn("""
                {"claims":[{"question":1,"text":"甲工厂的质检得分为9.3。","evidenceIds":["S1E1"],"tableFact":1},
                           {"question":2,"text":"猜测的排名。","evidenceIds":["S1E1"],"tableFact":9}]}
                """);
        var plan = new ReportSectionPlan("核对", "1. 表3的对象得分和排名\n2. 表3的另外一项");
        var result = anchored.generateAnchoredBatch(DocumentTaskType.REPORT, plan, List.of(SOURCE), Set.of("doc-1"),
                "batch", 150, ignored -> {});
        assertEquals(proven, result.claims().getFirst().text());
        verify(verifier).verifyStrict(anyString(), eq(proven), eq(List.of(citation)), eq(List.of(SOURCE)), eq(Set.of("doc-1")));
        verify(verifier).verifyStrict(anyString(), eq("猜测的排名。"), eq(java.util.Collections.singletonList(null)),
                eq(List.of(SOURCE)), eq(Set.of("doc-1")));
    }

    @Test void rejectsARealButWrongMetricOrObjectFromTheAuthorizedPhysicalTable() {
        var verifier = mock(ClaimVerifier.class);
        String correct = "中国的国家/地区影响度得分为7.22";
        String wrongMetric = "中国的国家/地区贡献度得分为5.32";
        String wrongObject = "美国的国家/地区影响度得分为9.33";
        var citation = new ClaimVerifier.Citation(1, SOURCE_TEXT, null,
                String.join("；", correct, wrongMetric, wrongObject),
                List.of(correct, wrongMetric, wrongObject), "表11 数据表");
        when(verifier.evidenceOptions(anyList(), anyString(), anyInt()))
                .thenReturn(new ClaimVerifier.EvidenceOptions("", Map.of("S1E1", citation)));
        when(verifier.verifyStrict(anyString(), anyString(), anyList(), anyList(), anySet())).thenAnswer(call ->
                new ClaimVerifier.VerifiedClaim(Claim.unsupported(call.getArgument(0), call.getArgument(1), "测试"), List.of()));
        var anchored = new LlmSectionGenerator(chatClient, new PromptTemplates(new DefaultResourceLoader()),
                new DocumentTaskProperties(), verifier);
        when(response.content()).thenReturn("""
                {"claims":[{"question":1,"text":"","evidenceIds":["S1E1"],"tableFact":2},
                           {"question":2,"text":"美国的国家/地区影响度得分为9.33","evidenceIds":["S1E1"]},
                           {"question":3,"text":"","evidenceIds":["S1E1"],"tableFact":1}]}
                """);
        var plan = new ReportSectionPlan("核对", """
                1. 描述表11中国的国家/地区影响度。
                2. 描述表11中国的国家/地区影响度。
                3. 描述表11中国的国家/地区影响度。
                """);
        var result = anchored.generateAnchoredBatch(DocumentTaskType.REPORT, plan, List.of(SOURCE), Set.of("doc-1"),
                "batch", 1000, ignored -> {});

        assertEquals(correct, result.claims().getLast().text());
        verify(verifier, times(2)).verifyStrict(anyString(), anyString(), eq(Collections.singletonList(null)),
                eq(List.of(SOURCE)), eq(Set.of("doc-1")));
        verify(verifier).verifyStrict(anyString(), eq(correct), eq(List.of(citation)),
                eq(List.of(SOURCE)), eq(Set.of("doc-1")));
    }

    @Test void canonicalizesOnlyAnExactObjectMetricValueAndRankFromTheCitedRow() {
        var verifier = mock(ClaimVerifier.class);
        String physical = "中国的国家研究前沿热度指数得分为23.10，排名第1";
        var citation = new ClaimVerifier.Citation(1, SOURCE_TEXT, null, physical, List.of(physical), "表8 数据表");
        when(verifier.evidenceOptions(anyList(), anyString(), anyInt()))
                .thenReturn(new ClaimVerifier.EvidenceOptions("", Map.of("S1E1", citation)));
        when(verifier.verifyStrict(anyString(), anyString(), anyList(), anyList(), anySet())).thenAnswer(call -> {
            String text = call.getArgument(1);
            SupportStatus result = physical.equals(text) ? SupportStatus.SUPPORTED : SupportStatus.INSUFFICIENT;
            return new ClaimVerifier.VerifiedClaim(new Claim(call.getArgument(0), text, List.of(), result, "测试", true), List.of());
        });
        var anchored = new LlmSectionGenerator(chatClient, new PromptTemplates(new DefaultResourceLoader()),
                new DocumentTaskProperties(), verifier);
        when(response.content()).thenReturn("""
                {"claims":[{"question":1,"text":"表8中，中国的热度指数得分是23.1分，排名第一。","evidenceIds":["S1E1"]},
                           {"question":2,"text":"中国的热度指数得分是23.10分，排名第二。","evidenceIds":["S1E1"]},
                           {"question":3,"text":"中国的热度指数得分是23.10万元，排名第一。","evidenceIds":["S1E1"]},
                           {"question":4,"text":"中国的热度指数得分不是23.10分，排名第一。","evidenceIds":["S1E1"]}]}
                """);
        var plan = new ReportSectionPlan("核对", """
                1. 描述表8中中国的热度指数。
                2. 描述表8中中国的热度指数。
                3. 描述表8中中国的热度指数。
                4. 描述表8中中国的热度指数。
                """);
        var result = anchored.generateAnchoredBatch(DocumentTaskType.REPORT, plan, List.of(SOURCE), Set.of("doc-1"),
                "batch", 1000, ignored -> {});

        assertEquals(physical, result.claims().getFirst().text());
        assertEquals(List.of(SupportStatus.SUPPORTED, SupportStatus.INSUFFICIENT, SupportStatus.INSUFFICIENT, SupportStatus.INSUFFICIENT),
                result.claims().stream().map(Claim::supportStatus).toList());
    }

    @Test void derivesOnlyTheUniqueRequestedTableValueFromAuthorizedOriginalEvidence() {
        var verifier = mock(ClaimVerifier.class);
        String score = "中国的国家/地区影响度得分为7.22";
        var citation = new ClaimVerifier.Citation(1, SOURCE_TEXT, null, score,
                List.of(score, "中国的国家/地区影响度排名为2",
                        "中国的国家/地区贡献度得分为5.32", "美国的国家/地区影响度得分为8.46"), "表11 数据表");
        when(verifier.evidenceOptions(anyList(), anyString(), anyInt()))
                .thenReturn(new ClaimVerifier.EvidenceOptions("", Map.of("S1E1", citation)));
        stubVerification(verifier);
        var anchored = new LlmSectionGenerator(chatClient, new PromptTemplates(new DefaultResourceLoader()),
                new DocumentTaskProperties(), verifier);
        when(response.content()).thenReturn("""
                {"claims":[{"question":1,"text":"没有找到依据。","evidenceIds":[]},
                           {"question":2,"text":"尚未找到依据。","evidenceIds":[]}]}
                """);
        var plan = new ReportSectionPlan("核对", """
                1. 表11中国的国家/地区影响度是多少？
                2. 表11中国的国家/地区影响度是多少，排名第几？
                """);
        var result = anchored.generateAnchoredBatch(DocumentTaskType.REPORT, plan, List.of(SOURCE), Set.of("doc-1"),
                "batch", 1000, ignored -> {});

        assertEquals(List.of(score, "尚未找到依据。"), result.claims().stream().map(Claim::text).toList());
        assertEquals(List.of(SupportStatus.SUPPORTED, SupportStatus.INSUFFICIENT),
                result.claims().stream().map(Claim::supportStatus).toList());
    }

    @Test
    void invalidCitationsDowngradeOnlyTheirClaimAndQuestionOrderIsRestored() {
        when(response.content()).thenReturn("""
                {"claims":[
                  {"question":2,"text":"审核负责人明确。","citations":[{"source":null,"quote":"审核负责人"},{"source":1,"quote":"该设备已经通过审核。"}]},
                  {"question":1,"text":"该设备已经通过审核。","citations":[{"source":1,"quote":"该设备已经通过审核。"}]},
                  {"question":3,"text":"审核条件明确。","citations":[{"source":"2","quote":"审核条件"}]},
                  {"question":4,"text":"审核手续完毕。","citations":[{"source":2,"quote":"审核手续"}]}
                ]}
                """);

        var section = generator.generateBatch(DocumentTaskType.REPORT, PLAN, List.of(SOURCE), Set.of("doc-1"),
                "section-1-batch-1", true);

        assertEquals(List.of(SOURCE_TEXT, "审核负责人明确。", "审核条件明确。", "审核手续完毕。"),
                section.claims().stream().map(Claim::text).toList());
        assertEquals(List.of(SupportStatus.SUPPORTED, SupportStatus.INSUFFICIENT,
                SupportStatus.INSUFFICIENT, SupportStatus.INSUFFICIENT), section.claims().stream().map(Claim::supportStatus).toList());
        assertEquals(1, section.evidences().size());
        assertEquals(4, section.evidences().getFirst().pageStart());
        assertEquals(section.claims().getFirst().evidenceIds(), section.claims().get(1).evidenceIds());
        verify(response).content();
    }

    @Test
    void malformedJsonIsRetriedOnceAndBatchesHaveDistinctClaimIds() {
        when(response.content()).thenReturn("{", SUPPORTED_RESPONSE, SUPPORTED_RESPONSE);

        var first = generator.generateBatch(DocumentTaskType.REPORT, PLAN, List.of(SOURCE), Set.of("doc-1"),
                "section-1-batch-1", true);
        var second = generator.generateBatch(DocumentTaskType.REPORT, PLAN, List.of(SOURCE), Set.of("doc-1"),
                "section-1-batch-2", true);

        assertEquals(SupportStatus.SUPPORTED, first.claims().getFirst().supportStatus());
        assertEquals(SupportStatus.SUPPORTED, second.claims().getFirst().supportStatus());
        assertNotEquals(first.claims().getFirst().claimId(), second.claims().getFirst().claimId());
        verify(response, times(3)).content();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anchoredRetryOffersOnlyTheTopEvidencePerQuestionAndRejectsDroppedIds(boolean timeout) {
        var verifier = mock(ClaimVerifier.class);
        var approval = new ClaimVerifier.Citation(1, SOURCE_TEXT);
        String confirmerText = "由供应商确认。";
        var confirmerChunk = new RetrievedChunk("chunk-2", "doc-1", "report.pdf", confirmerText, "审核", 5, 5, 1, 0.8);
        var confirmer = new ClaimVerifier.Citation(2, confirmerText);
        var supplement = new ClaimVerifier.Citation(1, "流程安排另行通知。");
        var other = new ClaimVerifier.Citation(1, "文档附有审核记录。");
        var approvalChunk = new RetrievedChunk("chunk-1", "doc-1", "report.pdf",
                SOURCE_TEXT + "\n" + other.quote() + "\n" + supplement.quote(), "审核", 4, 4, 0, 0.9);
        Map<String, ClaimVerifier.Citation> options = new LinkedHashMap<>();
        options.put("S1E1", approval);
        options.put("S2E2", confirmer);
        options.put("S1E3", supplement);
        options.put("S1E4", other);
        when(verifier.evidenceOptions(anyList(), anyString(), anyInt())).thenReturn(new ClaimVerifier.EvidenceOptions("", options));
        stubVerification(verifier);
        var anchored = new LlmSectionGenerator(chatClient, new PromptTemplates(new DefaultResourceLoader()),
                new DocumentTaskProperties(), verifier);
        String answer = """
                {"claims":[{"question":2,"text":"由供应商确认。","evidenceIds":["S2E2"]},
                           {"question":1,"text":"文档附有审核记录。","evidenceIds":["S1E4"]}]}
                """;
        if (timeout) {
            when(response.content()).thenThrow(new IllegalStateException(new SocketTimeoutException("timeout"))).thenReturn(answer);
        } else {
            when(response.content()).thenReturn("{", answer);
        }

        var questions = List.of(new SectionGenerator.QuestionEvidence(1, "是否通过审核", List.of("chunk-1")),
                new SectionGenerator.QuestionEvidence(2, "谁确认", List.of("chunk-2")));
        var attempts = new ArrayList<List<ClaimVerifier.AnchorSpan>>();
        var result = anchored.generateAnchoredBatch(DocumentTaskType.REPORT, PLAN, List.of(approvalChunk, confirmerChunk),
                Set.of("doc-1"), "batch", 200, questions, attempts::add);

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt(), times(2)).user(prompt.capture());
        assertTrue(prompt.getAllValues().getFirst().contains("[S1E4]"));
        assertFalse(prompt.getAllValues().getFirst().contains("[S1E3]"));
        assertTrue(prompt.getAllValues().getLast().contains("[S1E1]"));
        assertTrue(prompt.getAllValues().getLast().contains("[S2E2]"));
        assertFalse(prompt.getAllValues().getLast().contains("[S1E4]"));
        assertFalse(prompt.getAllValues().getLast().contains("[S1E3]"));
        assertEquals(List.of(SupportStatus.INSUFFICIENT, SupportStatus.SUPPORTED),
                result.claims().stream().map(Claim::supportStatus).toList());
        var approvalSpan = new ClaimVerifier.AnchorSpan("chunk-1", "doc-1", 4, 4, 0, SOURCE_TEXT.length(), 1);
        var otherSpan = new ClaimVerifier.AnchorSpan("chunk-1", "doc-1", 4, 4, SOURCE_TEXT.length() + 1, other.quote().length(), 1);
        var confirmerSpan = new ClaimVerifier.AnchorSpan("chunk-2", "doc-1", 5, 5, 0, confirmerText.length(), 2);
        assertEquals(List.of(List.of(approvalSpan, otherSpan, confirmerSpan), List.of(approvalSpan, confirmerSpan)), attempts);
    }

    @Test
    void anchoredBatchReservesEvidenceForEachQuestionAndRejectsOtherQuestionsIds() {
        String confirmerText = "结果由供应商确认。";
        var confirmerChunk = new RetrievedChunk("chunk-2", "doc-1", "report.pdf", confirmerText, "审核", 5, 5, 1, 0.8);
        var approval = new ClaimVerifier.Citation(1, SOURCE_TEXT);
        var supplement = new ClaimVerifier.Citation(1, "设备例行审核流程另行安排。");
        var confirmer = new ClaimVerifier.Citation(2, confirmerText);
        Map<String, ClaimVerifier.Citation> options = new LinkedHashMap<>();
        options.put("S1E1", approval);
        options.put("S1E2", supplement);
        options.put("S2E3", confirmer);
        var verifier = mock(ClaimVerifier.class);
        when(verifier.evidenceOptions(anyList(), anyString(), anyInt()))
                .thenReturn(new ClaimVerifier.EvidenceOptions("", options));
        stubVerification(verifier);
        var anchored = new LlmSectionGenerator(chatClient, new PromptTemplates(new DefaultResourceLoader()),
                new DocumentTaskProperties(), verifier);
        when(response.content()).thenReturn("""
                {"claims":[{"question":2,"text":"结果由供应商确认。","evidenceIds":["S2E3"]},
                           {"question":1,"text":"该设备已经通过审核。","evidenceIds":["S1E1"]},
                           {"question":1,"text":"供应商审批了设备。","evidenceIds":["S1E1","S2E3"]}]}
                """);
        var questions = List.of(new SectionGenerator.QuestionEvidence(1, "是否通过审核", List.of("chunk-1")),
                new SectionGenerator.QuestionEvidence(2, "谁确认", List.of("chunk-2")));
        int budget = ClaimVerifier.renderOption("S1E1", approval).length() + ClaimVerifier.renderOption("S2E3", confirmer).length();

        var result = anchored.generateAnchoredBatch(DocumentTaskType.REPORT, PLAN, List.of(SOURCE, confirmerChunk),
                Set.of("doc-1"), "batch", budget, questions, ignored -> {});

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(prompt.capture());
        assertTrue(prompt.getValue().contains("第 1 题可用证据编号：S1E1\n"));
        assertTrue(prompt.getValue().contains("第 2 题可用证据编号：S2E3\n"));
        assertFalse(prompt.getValue().contains("S1E2"));
        assertEquals(List.of(SupportStatus.SUPPORTED, SupportStatus.INSUFFICIENT, SupportStatus.SUPPORTED),
                result.claims().stream().map(Claim::supportStatus).toList());
    }

    @Test
    void anchoredResponseKeepsValidFactsWhenOtherQuestionIndexesAreInvalidOrOmitted() {
        var verifier = mock(ClaimVerifier.class);
        var approval = new ClaimVerifier.Citation(1, SOURCE_TEXT);
        when(verifier.evidenceOptions(anyList(), anyString(), anyInt())).thenReturn(new ClaimVerifier.EvidenceOptions(
                "[S1E1] " + SOURCE_TEXT, Map.of("S1E1", approval)));
        stubVerification(verifier);
        var anchored = new LlmSectionGenerator(chatClient, new PromptTemplates(new DefaultResourceLoader()),
                new DocumentTaskProperties(), verifier);
        when(response.content()).thenReturn("""
                {"claims":[{"question":1,"text":"该设备已经通过审核。","evidenceIds":["unknown"]},
                           {"question":1,"text":"该设备已经通过审核。","evidenceIds":["S1E1"]},
                           {"question":3,"text":"流程安排另行通知。","evidenceIds":["S1E1"]},
                           {"text":"负责人已签字。","evidenceIds":["S1E1"]}]}
                """);

        var result = anchored.generateAnchoredBatch(DocumentTaskType.REPORT, PLAN, List.of(SOURCE), Set.of("doc-1"),
                "batch", 200, ignored -> {});

        assertEquals(SOURCE_TEXT, result.claims().getFirst().text());
        assertEquals(SupportStatus.SUPPORTED, result.claims().getFirst().supportStatus());
        assertEquals(1, result.claims().stream().filter(claim -> claim.text().equals(SOURCE_TEXT)).count());
        assertTrue(result.claims().get(1).text().contains("谁确认"));
        assertTrue(result.claims().subList(1, 4).stream().allMatch(claim -> claim.supportStatus() == SupportStatus.INSUFFICIENT));
        verify(response).content();
    }

    @Test void unnumberedSectionRetainsAllOriginalFactsWithinItsBudget() {
        String text = "每天核对库存。供应商负责签字。周末暂停收货。异常单独登记。";
        var chunk = new RetrievedChunk("chunk-1", "doc-1", "report.pdf", text, "审核", 4, 4, 0, 0.9);
        Map<String, ClaimVerifier.Citation> citations = new LinkedHashMap<>();
        for (String fact : text.split("(?<=。)")) {
            citations.put("S1E" + (citations.size() + 1), new ClaimVerifier.Citation(1, fact));
        }
        var verifier = mock(ClaimVerifier.class);
        when(verifier.evidenceOptions(anyList(), anyString(), anyInt()))
                .thenReturn(new ClaimVerifier.EvidenceOptions("", citations));
        stubVerification(verifier);
        var anchored = new LlmSectionGenerator(chatClient, new PromptTemplates(new DefaultResourceLoader()),
                new DocumentTaskProperties(), verifier);
        when(response.content()).thenReturn("""
                {"claims":[{"question":1,"text":"每天核对库存。","evidenceIds":["S1E1"]},
                           {"question":1,"text":"供应商负责签字。","evidenceIds":["S1E2"]},
                           {"question":1,"text":"周末暂停收货。","evidenceIds":["S1E3"]},
                           {"question":1,"text":"异常单独登记。","evidenceIds":["S1E4"]}]}
                """);
        var attempts = new ArrayList<List<ClaimVerifier.AnchorSpan>>();
        var result = anchored.generateAnchoredBatch(DocumentTaskType.REPORT,
                new ReportSectionPlan("核查", "逐条说明所有要求。"), List.of(chunk), Set.of("doc-1"), "batch",
                120, attempts::add);

        assertEquals(4, result.claims().stream().filter(claim -> claim.supportStatus() == SupportStatus.SUPPORTED).count());
        assertEquals(4, attempts.getFirst().size());
        assertTrue(attempts.getFirst().stream().allMatch(span -> span.question() == null));
    }

    private static void stubVerification(ClaimVerifier verifier) {
        when(verifier.verifyStrict(anyString(), anyString(), anyList(), anyList(), anySet())).thenAnswer(call -> {
            List<ClaimVerifier.Citation> citations = call.getArgument(2);
            SupportStatus status = !citations.isEmpty() && citations.stream().noneMatch(java.util.Objects::isNull)
                    ? SupportStatus.SUPPORTED : SupportStatus.INSUFFICIENT;
            return new ClaimVerifier.VerifiedClaim(new Claim(call.getArgument(0), call.getArgument(1), List.of(),
                    status, "测试", true), List.of());
        });
    }

    @Test
    void persistentTimeoutIsExposedAsABatchFailureAfterOneRetry() {
        when(response.content()).thenThrow(new IllegalStateException(new SocketTimeoutException("timeout")));

        var failure = assertThrows(SectionGenerator.BatchFailure.class,
                () -> generator.generateBatch(DocumentTaskType.REPORT, PLAN, List.of(SOURCE), Set.of("doc-1"),
                        "section-1-batch-1", true));

        assertEquals(SectionGenerator.BatchFailureKind.TIMEOUT, failure.failureKind());
        verify(response, times(2)).content();
    }

    @Test
    void unexpectedProgrammingErrorsAreNotRetried() {
        var error = new NullPointerException("test plumbing");
        when(response.content()).thenThrow(error);

        assertSame(error, assertThrows(NullPointerException.class,
                () -> generator.generateBatch(DocumentTaskType.REPORT, PLAN, List.of(SOURCE), Set.of("doc-1"),
                        "section-1-batch-1", true)));
        verify(response).content();
    }

    @Test
    void legacyGenerationKeepsItsOutputSchemaAndConfiguredBudget() {
        when(response.entity(LlmSectionGenerator.SectionDraft.class)).thenReturn(
                new LlmSectionGenerator.SectionDraft(List.of(), ""));

        generator.generate(DocumentTaskType.REPORT, PLAN, List.of(SOURCE), Set.of("doc-1"));

        var request = chatClient.prompt();
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(request).user(prompt.capture());
        assertTrue(prompt.getValue().contains("[资料 1]\n该\n"));
        assertFalse(prompt.getValue().contains(SOURCE_TEXT));
        verify(response).entity(LlmSectionGenerator.SectionDraft.class);
        verify(response, never()).content();
    }
}
