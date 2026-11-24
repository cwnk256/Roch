package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.dto.ReportRetrievalSettings;
import com.wuyunbin.rag.dto.RetrievalOptions;
import com.wuyunbin.rag.retrieval.RetrievalMode;
import com.wuyunbin.rag.retrieval.RetrievalPipeline;
import com.wuyunbin.rag.service.ChunkTokenizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class TaskEvidenceRetrievalTest {
    @Test
    void keepsExplicitQuestionsInOrderAndRemovesTheRepeatedSectionRequirements() {
        var questions = TaskEvidenceRetrieval.questions("""
                只用正式合同。
                1. 履约保证金是多少？
                2、最终签收人是谁？
                总要求：只用正式合同。
                1. 履约保证金是多少？
                2、最终签收人是谁？
                """);
        assertEquals(List.of("履约保证金是多少？", "最终签收人是谁？"), questions.items());
        assertEquals("只用正式合同。", questions.scope());
        assertEquals(List.of("首批数量", "返修时限", "中期付款比例", "最终签收人", "最终报告附件"),
                TaskEvidenceRetrieval.questions("逐项提取首批数量、返修时限、中期付款比例、最终签收人和最终报告附件。").items());
        assertEquals(List.of(), TaskEvidenceRetrieval.questions("介绍设备的 2.5 年维护安排。").items());
    }

    @Test
    void reservesOnePassagePerQuestionBeforeTakingExtrasAndExcludesOtherDocuments() {
        var hits = List.of(
                List.of(chunk("wrong", "hidden", 9), chunk("first", "a", 1), chunk("same-page", "a", 1), chunk("extra", "a", 2)),
                List.of(chunk("second", "a", 3)));
        var selected = TaskEvidenceRetrieval.balance(hits, Set.of("a"), 12);
        assertEquals(List.of("first", "second", "extra"), selected.stream().map(RetrievedChunk::chunkId).toList());
        assertEquals(List.of("first", "second"),
                TaskEvidenceRetrieval.balance(hits, Set.of("a"), 8).stream().map(RetrievedChunk::chunkId).toList());
    }

    @Test
    void inlineQuestionsKeepTrailingExclusionsInTheScope() {
        var questions = TaskEvidenceRetrieval.questions("逐项核对巡检周期、到场时限，排除2026版和K22。");
        assertEquals(List.of("巡检周期", "到场时限"), questions.items());
        assertTrue(questions.scope().contains("排除2026版和K22"));
    }

    @Test
    void aRepeatedTopHitDoesNotDisplaceAnotherQuestionsDistinctEvidenceOnTheSamePage() {
        var first = chunk("first", "a", 3);
        var second = chunk("second", "a", 3);
        var selected = TaskEvidenceRetrieval.balance(List.of(List.of(first), List.of(first, second)), Set.of("a"), 8);
        assertEquals(List.of(first, second), selected);
    }

    @Test
    void hybridCandidatesUseTheScopedPipelineAtTheCandidateLimit() {
        var pipeline = mock(RetrievalPipeline.class);
        var retrieval = new TaskEvidenceRetrieval(pipeline, mock(ChunkTokenizer.class));
        var settings = new ReportRetrievalSettings("hybrid-only", 8, 0.35, 24000, 8000);
        var options = new RetrievalOptions(20, 0.35, Set.of("a"), RetrievalMode.HYBRID);
        when(pipeline.search("表 11", options)).thenReturn(List.of(chunk("wrong", "hidden", 9), chunk("right", "a", 4)));

        assertEquals(List.of("right"), retrieval.hybrid("表 11", Set.of("a"), settings).stream()
                .map(RetrievedChunk::chunkId).toList());
        verify(pipeline).search("表 11", options);
    }

    private static RetrievedChunk chunk(String id, String doc, int page) {
        return new RetrievedChunk(id, doc, "file.pdf", "正文正文", "", page, page, 0, 0.9);
    }
}
