package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.dto.ReportRetrievalSettings;
import com.wuyunbin.rag.dto.RetrievalOptions;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkEntity;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
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
        var retrieval = new TaskEvidenceRetrieval(pipeline, mock(ChunkTokenizer.class),
                mock(DocumentChunkJpaRepository.class));
        var settings = new ReportRetrievalSettings("hybrid-only", 8, 0.35, 24000, 8000);
        var options = new RetrievalOptions(20, 0.35, Set.of("a"), RetrievalMode.HYBRID);
        when(pipeline.search("表 11", options)).thenReturn(List.of(chunk("wrong", "hidden", 9), chunk("right", "a", 4)));

        assertEquals(List.of("right"), retrieval.hybrid("表 11", Set.of("a"), settings).stream()
                .map(RetrievedChunk::chunkId).toList());
        verify(pipeline).search("表 11", options);
    }

    @Test
    void supplementsAnExactCaptionWithAdjacentRowsWithinTheExistingBatchBudget() {
        var repository = mock(DocumentChunkJpaRepository.class);
        var tokenizer = mock(ChunkTokenizer.class);
        var retrieval = new TaskEvidenceRetrieval(mock(RetrievalPipeline.class), tokenizer, repository);
        var existing = chunk("existing", "a", 5);
        var query = "净水系统表 12 中，北区的处理量是多少？";
        var caption = row("caption", "a", 8, 4, "表12 净水系统处理量\n处理站 | 处理量");
        var rows = row("rows", "a", 8, 5, "北区 | 48 吨\n南区 | 32 吨");
        var decoy = row("different-table", "a", 5, 2, "表120 净水系统处理量\n北区 | 97 吨");
        when(tokenizer.tokenize(query)).thenReturn("净水 系统 表 12 中 北区 处理量 多少");
        when(repository.searchReportAnchors(eq("净水 & 系统 & 12"), eq(Set.of("a")), anyInt()))
                .thenReturn(List.of(decoy, caption));
        when(repository.findTop4ByDocMd5AndPageStartAndChunkIndexOrderByChunkIdAsc("a", 8, 5))
                .thenReturn(List.of(row("other-document", "b", 8, 5, "北区 | 77 吨"), rows));

        assertEquals(List.of("existing", "caption", "rows"), retrieval.supplement(
                List.of(query), List.of(existing), Set.of("a"), 100).selected().stream()
                .map(RetrievedChunk::chunkId).toList());
        assertEquals(List.of(existing), retrieval.supplement(
                List.of(query), List.of(existing), Set.of("a"), 25).selected());
    }

    @Test
    void includesSupportingProseWhenTheSelectedPassageOnlyNamesTheSubject() {
        var repository = mock(DocumentChunkJpaRepository.class);
        var tokenizer = mock(ChunkTokenizer.class);
        var retrieval = new TaskEvidenceRetrieval(mock(RetrievalPipeline.class), tokenizer, repository);
        var query = "地下水监测领域西区的达标率是多少？";
        when(tokenizer.tokenize(query)).thenReturn("地下水 监测 领域 西区 达标率 多少");
        var selected = new RetrievedChunk("existing", "a", "file.pdf", "地下水监测领域的各区概况如下。",
                "", 6, 6, 10, 0.9);
        when(repository.searchReportAnchors(eq("地下水 & 监测 & 领域"), eq(Set.of("a")), anyInt())).thenReturn(List.of(
                row("wrong-region", "a", 3, 4, "地下水监测领域东区的达标率是 81%。"),
                row("unpaginated", "a", null, 12, "地下水监测领域西区的达标率是 77%。"),
                row("water-west", "a", 6, 11, "在地下水监测领域，西区的达标率是 89%。")));
        assertEquals(List.of("existing", "water-west"), retrieval.supplement(
                List.of(query), List.of(selected), Set.of("a"), 100).selected().stream()
                .map(RetrievedChunk::chunkId).toList());
    }

    @Test
    void choosesOneAnchoredPassageForAQuestionWithAnAlignedTable() {
        var repository = mock(DocumentChunkJpaRepository.class);
        var tokenizer = mock(ChunkTokenizer.class);
        var retrieval = new TaskEvidenceRetrieval(mock(RetrievalPipeline.class), tokenizer, repository);
        var query = "地下水监测领域西区的达标率是多少？";
        when(tokenizer.tokenize(query)).thenReturn("地下水 监测 领域 西区 达标率 多少");
        when(repository.searchReportAnchors(eq("地下水 & 监测 & 领域"), eq(Set.of("a")), anyInt())).thenReturn(List.of(
                row("incidental-prose", "a", 3, 4, "地下水监测领域西区的达标率分 5 档统计。"),
                row("aligned-table", "a", 6, 11, "表7 地下水监测领域达标情况\n地区 | 达标率\n西区 | 89%")));

        assertEquals(List.of("aligned-table"), retrieval.supplement(
                List.of(query), List.of(), Set.of("a"), 100).selected().stream().map(RetrievedChunk::chunkId).toList());
    }

    @Test
    void usesOnlyTheRowsBelowTheRequestedCaptionWhenAChunkContainsTwoTables() {
        var repository = mock(DocumentChunkJpaRepository.class);
        var tokenizer = mock(ChunkTokenizer.class);
        var retrieval = new TaskEvidenceRetrieval(mock(RetrievalPipeline.class), tokenizer, repository);
        var firstQuestion = "灌溉设施表 16 中，北区的流量是多少？";
        var secondQuestion = "灌溉设施表 17 中，北区的流量是多少？";
        var tables = row("tables", "a", 7, 12, """
                表16 灌溉设施流量。表17 灌溉设施流量
                地区 | 流量
                北区 | 62
                """);
        when(tokenizer.tokenize(firstQuestion)).thenReturn("灌溉 设施 表 16 北区 流量 多少");
        when(tokenizer.tokenize(secondQuestion)).thenReturn("灌溉 设施 表 17 北区 流量 多少");
        when(repository.searchReportAnchors(anyString(), eq(Set.of("a")), anyInt())).thenReturn(List.of(tables));

        assertEquals(List.of(), retrieval.supplement(List.of(firstQuestion), List.of(), Set.of("a"), 100).selected());
        assertEquals(List.of("tables"), retrieval.supplement(List.of(secondQuestion), List.of(), Set.of("a"), 100)
                .selected().stream().map(RetrievedChunk::chunkId).toList());
        verify(repository, never()).findTop4ByDocMd5AndPageStartAndChunkIndexOrderByChunkIdAsc(
                anyString(), anyInt(), anyInt());
    }

    private static DocumentChunkEntity row(String id, String doc, Integer page, int index, String content) {
        var row = new DocumentChunkEntity();
        row.setChunkId(id);
        row.setDocMd5(doc);
        row.setContent(content);
        row.setFileName("file.pdf");
        row.setSectionPath("统计");
        row.setPageStart(page);
        row.setPageEnd(page);
        row.setChunkIndex(index);
        return row;
    }

    private static RetrievedChunk chunk(String id, String doc, int page) {
        return new RetrievedChunk(id, doc, "file.pdf", "正文正文", "", page, page, 0, 0.9);
    }
}
