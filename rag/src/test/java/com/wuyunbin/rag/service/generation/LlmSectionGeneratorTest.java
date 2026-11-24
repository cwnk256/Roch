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
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
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
        assertEquals(List.of(new ClaimVerifier.AnchorSpan("c", digest, null, null, 0, SOURCE_TEXT.length())), shown);
        assertEquals(List.of(SupportStatus.SUPPORTED,SupportStatus.INSUFFICIENT),result.claims().stream().map(Claim::supportStatus).toList());
        assertEquals(SOURCE_TEXT,result.evidences().getFirst().quote());
        assertTrue(result.evidences().getFirst().nativeLocation().startsWith("原文字符"));
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
