package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkEntity;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
import com.wuyunbin.rag.service.generation.model.SourceStatus;
import com.wuyunbin.rag.service.generation.model.SupportStatus;
import com.wuyunbin.rag.service.ocr.OcrArtifactStore;
import com.wuyunbin.rag.service.ocr.OcrPage;
import com.wuyunbin.rag.service.ocr.OcrPage.Cell;
import com.wuyunbin.rag.service.storage.DocumentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScannedTableEvidenceTest {
    @TempDir Path root;

    @Test
    void strictEvidenceBindsMergedHeadersToTheOriginalValueCell() throws Exception {
        var value = cell(2, 1, 1, "23.10", .35, .3, .55, .4, .99);
        var page = table(value);
        var fixture = verifier(page);
        var quote = "甲设备 | 评估结果 费用（万元） | 23.10";
        var citation = fixture.verifier().evidenceOptions(List.of(fixture.hit())).citations().values().stream()
                .filter(candidate -> candidate.quote().equals(quote)).findFirst().orElseThrow();

        assertThat(citation.physicalFacts()).containsExactly("甲设备的评估结果 费用（万元）为23.10");
        assertThat(citation.ocrTable().canonicalAnswer("甲设备的评估结果的费用（万元）是23.10。"))
                .isEqualTo(citation.physicalFacts().getFirst());
        assertThat(citation.ocrTable().canonicalAnswer("甲设备的评估结果的费用（元）是23.10。")).isNull();
        assertThat(citation.ocrTable().answersQuestion("甲设备的费用是多少万元？")).isTrue();
        assertThat(citation.ocrTable().answersQuestion("备用甲设备的费用是多少万元？")).isFalse();
        var power = new OcrPage.TableFact("设备1", List.of("功率"), "2 GW");
        assertThat(power.answersQuestion("设备1的功率是多少W？")).isFalse();
        assertThat(power.canonicalAnswer("设备1的功率为2 gW。")).isNull();
        assertThat(citation.ocrTable().answersQuestion("甲设备的排名是多少？")).isFalse();
        assertThat(citation.ocrTable().answersQuestion("乙设备的费用是多少万元？")).isFalse();
        assertThat(citation.ocrTable().answersQuestion("甲设备的费用是多少元？")).isFalse();
        assertThat(fixture.strict("甲设备的评估结果 费用（万元）为23.10。", new ClaimVerifier.Citation(1, quote))
                .claim().supportStatus()).isEqualTo(SupportStatus.INSUFFICIENT);
        var verified = fixture.strict("甲设备的评估结果 费用（万元）为23.10。", citation);
        assertThat(verified.claim().supportStatus()).isEqualTo(SupportStatus.SUPPORTED);
        var evidence = verified.evidences().getFirst();
        assertThat(evidence.sourceStatus()).isEqualTo(SourceStatus.MATCHED);
        assertThat(evidence.pageStart()).isEqualTo(3);
        assertThat(evidence.ocr().region()).isEqualTo(value.box());
        assertThat(evidence.ocr().table().row()).isEqualTo(3);
        assertThat(evidence.ocr().table().column()).isEqualTo(2);
        assertThat(fixture.strict("乙设备的评估结果 费用（万元）为23.10。", citation).claim().supportStatus())
                .isEqualTo(SupportStatus.INSUFFICIENT);
        assertThat(fixture.verifier().verifyAnchoredComparison("C1",
                "对象：甲设备；指标：评估结果 费用（万元）；原值：23.10", citation, List.of(fixture.hit()),
                Set.of(fixture.hit().documentId()), "", true).claim().supportStatus())
                .isEqualTo(SupportStatus.SUPPORTED);
        assertThat(fixture.verifier().verifyAnchoredComparison("C1",
                "对象：甲设备；指标：评估结果 费用（万元）；原值：23.10元", citation, List.of(fixture.hit()),
                Set.of(fixture.hit().documentId()), "", true).claim().supportStatus())
                .isEqualTo(SupportStatus.INSUFFICIENT);
        assertThat(fixture.strict("甲设备的评估结果 排名为23.10。", citation).claim().supportStatus())
                .isEqualTo(SupportStatus.INSUFFICIENT);
        assertThat(fixture.strict("甲设备的评估结果 费用（元）为23.10。", citation).claim().supportStatus())
                .isEqualTo(SupportStatus.INSUFFICIENT);
        assertThat(fixture.verifier().verify("C1", "甲设备的评估结果 费用（万元）为23.10", List.of(citation),
                List.of(fixture.hit()), Set.of(fixture.hit().documentId())).claim().supportStatus())
                .isEqualTo(SupportStatus.INSUFFICIENT);
        verifyNoInteractions(fixture.judge());
    }

    @Test
    void rotatedGridIsCheckedUprightButKeepsDisplayedCellCoordinates() throws Exception {
        var value = cell(2, 1, 1, "23.10", .35, .3, .55, .4, .99);
        var upright = table(value).tables().getFirst();
        var rotatedTable = new OcrPage.Table(rotateClockwise(upright.box()), upright.cells().stream()
                .map(c -> new Cell(c.row(), c.column(), c.rowSpan(), c.columnSpan(),
                        c.text(), rotateClockwise(c.box()), c.confidence())).toList());
        var page = new OcrPage(3, "fixture", 90, List.of(), List.of(rotatedTable));
        var fixture = verifier(page);
        var citation = fixture.verifier().evidenceOptions(List.of(fixture.hit())).citations().values().stream()
                .filter(candidate -> candidate.quote().equals("甲设备 | 评估结果 费用（万元） | 23.10"))
                .findFirst().orElseThrow();
        var verified = fixture.strict("甲设备的评估结果 费用（万元）为23.10。", citation);
        assertThat(verified.claim().supportStatus()).isEqualTo(SupportStatus.SUPPORTED);
        assertThat(verified.evidences().getFirst().ocr().region()).isEqualTo(rotateClockwise(value.box()));
    }

    private List<Double> rotateClockwise(List<Double> box) {
        return List.of(1 - box.get(3), box.get(0), 1 - box.get(1), box.get(2));
    }

    @Test
    void lowConfidenceOrGeometryConflictLeavesTableEvidenceForManualReview() throws Exception {
        for (var value : List.of(cell(2, 1, 1, "23.10", .35, .3, .55, .4, .50),
                cell(2, 1, 1, "23.10", .56, .3, .75, .4, .99))) {
            var fixture = verifier(table(value));
            var verified = fixture.strict("甲设备的费用为23.10", new ClaimVerifier.Citation(1, "23.10"));
            assertThat(verified.claim().supportStatus()).isEqualTo(SupportStatus.INSUFFICIENT);
            assertThat(verified.evidences().getFirst().ocr().reviewRequired()).isTrue();
            verifyNoInteractions(fixture.judge());
        }
    }

    @Test
    void disagreeingPageAndCellTextDoNotCreateSupportedTableFacts() {
        var value = cell(2, 1, 1, "23.10元", .35, .3, .55, .4, .99);
        var page = new OcrPage(3, "fixture",
                List.of(new OcrPage.Block("23.10万元", List.of(.36, .32, .54, .38), .99)), table(value).tables());
        assertThat(page.anchors()).noneMatch(anchor -> anchor.tableFact() != null);
        assertThat(page.anchors()).anyMatch(anchor -> anchor.reviewRequired() && anchor.quote().equals("23.10元"));
        var shortRead = new OcrPage(3, "fixture",
                List.of(new OcrPage.Block("2", List.of(.36, .32, .54, .38), .99)),
                table(cell(2, 1, 1, "20", .35, .3, .55, .4, .99)).tables());
        assertThat(shortRead.anchors()).noneMatch(anchor -> anchor.tableFact() != null);
    }

    @Test
    void aYearHeaderIsNotVerifiedAsAnAmount() {
        var page = new OcrPage(3, "fixture", List.of(), List.of(new OcrPage.Table(List.of(.1, .1, .85, .4), List.of(
                cell(0, 0, 1, "对象", .1, .1, .34, .2, .99),
                cell(0, 1, 2, "销售金额", .35, .1, .85, .2, .99),
                cell(1, 0, 1, "统计年度", .1, .2, .34, .3, .99),
                cell(1, 1, 1, "2024", .35, .2, .55, .3, .99),
                cell(1, 2, 1, "2025", .56, .2, .85, .3, .99),
                cell(2, 0, 1, "甲设备", .1, .3, .34, .4, .99),
                cell(2, 1, 1, "23.10万元", .35, .3, .55, .4, .99),
                cell(2, 2, 1, "24.02万元", .56, .3, .85, .4, .99)))));
        assertThat(page.anchors()).noneMatch(anchor -> anchor.tableFact() != null && anchor.quote().startsWith("年度"));
        assertThat(page.anchors()).anyMatch(anchor -> anchor.tableFact() != null
                && anchor.quote().equals("甲设备 | 销售金额 2024 | 23.10万元"));
    }

    private OcrPage table(Cell value) {
        return new OcrPage(3, "fixture", List.of(), List.of(new OcrPage.Table(List.of(.1, .1, .86, .41), List.of(
                cell(0, 0, 1, "对象", .1, .1, .34, .2, .99),
                cell(0, 1, 2, "评估结果", .35, .1, .85, .2, .99),
                cell(1, 0, 1, "名称", .1, .2, .34, .3, .99),
                cell(1, 1, 1, "费用（万元）", .35, .2, .55, .3, .99),
                cell(1, 2, 1, "排名", .56, .2, .85, .3, .99),
                cell(2, 0, 1, "甲设备", .1, .3, .34, .4, .99),
                value,
                cell(2, 2, 1, "2", .56, .3, .85, .4, .99)))));
    }

    private Cell cell(int row, int column, int columnSpan, String text, double left, double top, double right,
                      double bottom, double confidence) {
        return new Cell(row, column, 1, columnSpan, text, List.of(left, top, right, bottom), confidence);
    }

    private Fixture verifier(OcrPage page) throws Exception {
        var storage = mock(DocumentStorage.class);
        var chunks = mock(DocumentChunkJpaRepository.class);
        var judge = mock(ClaimSupportJudge.class);
        var bytes = "original fixture bytes".getBytes(StandardCharsets.UTF_8);
        var id = DigestUtils.md5DigestAsHex(bytes);
        var source = new DocumentStorage.StoredDocument(id, "scan.pdf", bytes);
        var artifacts = new OcrArtifactStore(root.toString());
        var version = artifacts.save(source, "fixture-pdf3", List.of(page));
        when(storage.load(id)).thenReturn(Optional.of(source));
        var row = new DocumentChunkEntity();
        row.setDocMd5(id);
        row.setChunkId("chunk");
        row.setFileName(source.fileName());
        row.setPageStart(3);
        row.setPageEnd(3);
        row.setContent(page.indexedText());
        row.setPipelineVersion(version);
        when(chunks.findById("chunk")).thenReturn(Optional.of(row));
        var hit = new RetrievedChunk("chunk", id, source.fileName(), row.getContent(), null, 3, 3, 0, 1);
        return new Fixture(new ClaimVerifier(chunks, judge, new OriginalPassages(storage, null, null, null, artifacts)),
                judge, hit);
    }

    private record Fixture(ClaimVerifier verifier, ClaimSupportJudge judge, RetrievedChunk hit) {
        ClaimVerifier.VerifiedClaim strict(String statement, ClaimVerifier.Citation citation) {
            return verifier.verifyStrict("C1", statement, List.of(citation), List.of(hit), Set.of(hit.documentId()));
        }
    }
}
