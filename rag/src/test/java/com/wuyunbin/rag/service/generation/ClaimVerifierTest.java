package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkEntity;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
import com.wuyunbin.rag.service.PdfDocumentParser;
import com.wuyunbin.rag.service.TableTextEnhancer;
import com.wuyunbin.rag.service.generation.model.SourceStatus;
import com.wuyunbin.rag.service.generation.model.SupportStatus;
import com.wuyunbin.rag.service.storage.DocumentStorage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.junit.jupiter.api.Test;
import org.springframework.util.DigestUtils;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaimVerifierTest {
    @Test void retrievesOriginalSpellingAndRejectsAmbiguousCaseFoldedIdentifiers() throws Exception {
        var storage = mock(com.wuyunbin.rag.service.storage.DocumentStorage.class);
        var parsed = new OriginalPassages(storage, null, null, null);
        var actual = "设备 K17 的频率为 50 Hz。标记 mW。标记 MW。";
        var bytes = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var id = org.springframework.util.DigestUtils.md5DigestAsHex(bytes);
        when(storage.load(id)).thenReturn(Optional.of(new com.wuyunbin.rag.service.storage.DocumentStorage.StoredDocument(
                id, "source.txt", bytes)));
        parsed.beginTask();
        try {
            assertEquals("K17", parsed.find(id, null, java.util.regex.Pattern.compile("k17", java.util.regex.Pattern.CASE_INSENSITIVE)).quote());
            assertNull(parsed.find(id, null, java.util.regex.Pattern.compile("标记 mw", java.util.regex.Pattern.CASE_INSENSITIVE)).quote());
            org.mockito.Mockito.verify(storage, times(1)).load(id);
        } finally {
            parsed.endTask();
        }
    }
    @Test void offersOnlyOriginalSentencesFromSelectedChunk() throws Exception {
        var storage = mock(com.wuyunbin.rag.service.storage.DocumentStorage.class);
        String sentence = "设备 K17 的频率为 50 Hz。";
        byte[] bytes = sentence.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = org.springframework.util.DigestUtils.md5DigestAsHex(bytes);
        when(storage.load(digest)).thenReturn(Optional.of(new com.wuyunbin.rag.service.storage.DocumentStorage.StoredDocument(
                digest, "source.txt", bytes)));
        var row = persisted(digest);
        row.setFileName("source.txt");
        row.setContent(sentence.toLowerCase(java.util.Locale.ROOT) + "虚构描述。");
        when(chunks.findById("c")).thenReturn(Optional.of(row));
        var options = new ClaimVerifier(chunks, judge, new OriginalPassages(storage, null, null, null))
                .evidenceOptions(List.of(new RetrievedChunk("c",digest,"source.txt",row.getContent(),null,null,null,0,1)));
        assertEquals(List.of(sentence), options.citations().values().stream().map(ClaimVerifier.Citation::quote).toList());
        assertFalse(options.prompt().contains("虚构"));
    }

    @Test void exposesOnlyWorksheetCellsAndPersistsTheirPhysicalRange() throws Exception {
        var storage = mock(com.wuyunbin.rag.service.storage.DocumentStorage.class);
        var bytesOut = new java.io.ByteArrayOutputStream();
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = book.createSheet("采购");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("设备");
            header.createCell(1).setCellValue("费用");
            var data = sheet.createRow(5);
            data.createCell(0).setCellValue("K17");
            data.createCell(1).setCellValue(50);
            book.write(bytesOut);
        }
        byte[] bytes = bytesOut.toByteArray();
        var id = org.springframework.util.DigestUtils.md5DigestAsHex(bytes);
        when(storage.load(id)).thenReturn(Optional.of(new com.wuyunbin.rag.service.storage.DocumentStorage.StoredDocument(
                id,"采购.xlsx",bytes)));
        String content = "| 设备 | 费用 |\n| --- | --- |\n| k17 | 50 |\nK17 的采购费用为 50。";
        var row = persisted(id);
        row.setFileName("采购.xlsx");
        row.setSectionPath("采购");
        row.setContent(content);
        when(chunks.findById("c")).thenReturn(Optional.of(row));
        var parser = new com.wuyunbin.rag.service.ExcelDocumentParser(new com.wuyunbin.rag.service.TableTextEnhancer());
        var instance = new ClaimVerifier(chunks, judge, new OriginalPassages(storage,null,null,parser));
        var selected = List.of(new RetrievedChunk("c",id,"采购.xlsx",content,"采购",null,null,0,1));
        var options = instance.evidenceOptions(selected);
        assertFalse(options.prompt().contains("采购费用为"));
        var citation = options.citations().values().stream().filter(c -> c.quote().contains("K17")).findFirst().orElseThrow();
        var fact = instance.verifyAnchoredComparison("C1", "K17 的费用为 50", citation, selected, Set.of(id), "", true);
        assertEquals(SupportStatus.SUPPORTED, fact.claim().supportStatus());
        assertEquals("采购!A6:B6", fact.evidences().getFirst().nativeLocation());
        assertEquals("| K17 | 50 |", fact.evidences().getFirst().quote());
        verifyNoInteractions(judge);
    }

    @Test void rejectsRepeatedNativeParagraphAndTruncatedQualification() {
        String full = "A设备的校准周期为30天，但仅在试运行时适用。";
        String truncated = "A设备的校准周期为30天";
        assertFalse(NativeTextSpan.find(full, truncated).isCompleteClause(full));
        assertTrue(NativeTextSpan.find(full, full).isCompleteClause(full));
        assertNull(NativeTextSpan.find(full + "\n" + full, full));
        var nativeSource = mock(OriginalPassages.class);
        when(nativeSource.nativeOfficeOrText("d")).thenReturn(full);
        var row = persisted("d");
        row.setFileName("source.txt");
        row.setContent(full);
        when(chunks.findById("c")).thenReturn(Optional.of(row));
        var instance = new ClaimVerifier(chunks, judge, nativeSource);
        var cropped = new RetrievedChunk("c","d","source.txt",truncated,null,null,null,0,1);
        assertTrue(instance.evidenceOptions(List.of(cropped)).citations().isEmpty());
    }

    @Test void anchorsAnOcrLineWithoutASentenceStopAndKeepsItsQualification() throws Exception {
        String line = "Inspection interval is 30 days";
        String qualifier = "but only during the trial period";
        byte[] bytes = pdfWithTextLines(RenderingMode.NEITHER, "Maintenance handbook", line, qualifier);
        String id = DigestUtils.md5DigestAsHex(bytes);
        var storage = mock(DocumentStorage.class);
        when(storage.load(id)).thenReturn(Optional.of(new DocumentStorage.StoredDocument(id, "manual.pdf", bytes)));
        var row = persisted(id);
        row.setFileName("manual.pdf");
        row.setPageStart(1);
        row.setPageEnd(1);
        row.setContent("### inspection interval is 30 days");
        when(chunks.findById("c")).thenReturn(Optional.of(row));
        var selected = List.of(new RetrievedChunk("c", id, "manual.pdf", row.getContent(), null, 1, 1, 0, 1));
        var verifier = new ClaimVerifier(chunks, judge,
                new OriginalPassages(storage, new PdfDocumentParser(new TableTextEnhancer()), null, null));

        var options = verifier.evidenceOptions(selected, "inspection interval", 1_000);
        assertEquals(List.of(line), options.citations().values().stream().map(ClaimVerifier.Citation::quote).toList());
        assertTrue(options.prompt().contains(qualifier));
        assertEquals(4, options.spans(selected).getFirst().start());
        var citation = options.citations().values().iterator().next();
        var passages = List.of(new ClaimSupportJudge.Passage(line, "Maintenance handbook\n" + line + "\n" + qualifier));
        String unqualifiedClaim = "The inspection interval is always 30 days.";
        when(judge.judge(unqualifiedClaim, passages))
                .thenReturn(new ClaimSupportJudge.Judgment(SupportStatus.INSUFFICIENT, "Applies only during trial."));
        var checked = verifier.verifyStrict("C1", unqualifiedClaim, List.of(citation), selected, Set.of(id));
        assertEquals(SourceStatus.MATCHED, checked.evidences().getFirst().sourceStatus());
        assertEquals(line, checked.evidences().getFirst().quote());
        assertEquals(SupportStatus.INSUFFICIENT, checked.claim().supportStatus());
        org.mockito.Mockito.verify(judge).judge(unqualifiedClaim, passages);
    }

    @Test void searchablePdfStillRejectsAnIncompleteSentence() throws Exception {
        String line = "Inspection interval is 30 days";
        byte[] bytes = pdfWithTextLines(RenderingMode.FILL, "Maintenance handbook", line, "but only during trial.");
        String id = DigestUtils.md5DigestAsHex(bytes);
        var storage = mock(DocumentStorage.class);
        when(storage.load(id)).thenReturn(Optional.of(new DocumentStorage.StoredDocument(id, "manual.pdf", bytes)));
        var row = persisted(id);
        row.setFileName("manual.pdf");
        row.setPageStart(1);
        row.setContent(line);
        when(chunks.findById("c")).thenReturn(Optional.of(row));
        var selected = List.of(new RetrievedChunk("c", id, "manual.pdf", line, null, 1, 1, 0, 1));
        var verifier = new ClaimVerifier(chunks, judge,
                new OriginalPassages(storage, new PdfDocumentParser(new TableTextEnhancer()), null, null));
        assertTrue(verifier.evidenceOptions(selected).citations().isEmpty());
    }

    @Test void keepsOcrDecimalAndAreaUnitBoundToTheClaim() {
        String quote = "造林面积为 28 ． 7 万亩 ，";
        var passages = List.of(new StructuredEvidence.Passage(quote, quote, quote, true));
        assertNull(StructuredEvidence.insufficiency("造林面积为28.7万亩。", passages));
        assertNotNull(StructuredEvidence.insufficiency("造林面积为28.7公顷。", passages));
        assertNotNull(StructuredEvidence.insufficiency("造林面积为7万亩。", passages));
        String item = "1 ． 7 万亩新增面积";
        assertNotNull(StructuredEvidence.insufficiency("新增面积为1.7万亩。",
                List.of(new StructuredEvidence.Passage(item, item, item, true))));
    }

    private static byte[] pdfWithTextLines(RenderingMode mode, String... lines) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setRenderingMode(mode);
                content.newLineAtOffset(60, 700);
                for (String line : lines) {
                    content.showText(line);
                    content.newLineAtOffset(0, -16);
                }
                content.endText();
            }
            document.save(bytes);
        }
        return bytes.toByteArray();
    }
    final DocumentChunkJpaRepository chunks = mock(DocumentChunkJpaRepository.class);
    final ClaimSupportJudge judge = mock(ClaimSupportJudge.class);
    final ClaimVerifier verifier = new ClaimVerifier(chunks, judge);
    final String original = "巡检周期 30\r\n 天。建议每月巡检。";
    final RetrievedChunk hit = new RetrievedChunk("c", "d", "index.pdf", original, null, null, null, 0, 0.99);

    DocumentChunkEntity persisted(String documentId) {
        var row = new DocumentChunkEntity();
        row.setDocMd5(documentId);
        row.setChunkId("c");
        row.setFileName("原文.pdf");
        row.setPipelineVersion("v3");
        row.setPageStart(5);
        row.setContent(original);
        return row;
    }

    ClaimVerifier.VerifiedClaim verify(int source, String quote) {
        return verifier.verify("C1", "必须每月巡检。", List.of(new ClaimVerifier.Citation(source, quote)),
                List.of(hit), Set.of("d"));
    }

    @Test void matchesWhitespaceButSnapshotsOriginalAndChecksSemanticsIndependently() {
        when(chunks.findById("c")).thenReturn(Optional.of(persisted("d")));
        when(judge.judge(anyString(), anyList())).thenReturn(
                new ClaimSupportJudge.Judgment(SupportStatus.PARTIAL, "原文只有建议。"));
        var verified = verify(1, "巡检周期 30 天。建议每月巡检。");
        var evidence = verified.evidences().getFirst();
        assertEquals(original, evidence.quote());
        assertEquals(SourceStatus.MATCHED, evidence.sourceStatus());
        assertEquals("原文.pdf", evidence.fileName());
        assertEquals(5, evidence.pageStart());
        assertEquals(64, evidence.textHash().length());
        assertEquals(SupportStatus.PARTIAL, verified.claim().supportStatus());
    }

    @Test void changedNumberAndOutOfContextQuoteCannotGainSupport() {
        when(chunks.findById("c")).thenReturn(Optional.of(persisted("d")));
        var tampered = verify(1, "巡检周期 60 天。");
        assertEquals(SourceStatus.QUOTE_MISMATCH, tampered.evidences().getFirst().sourceStatus());
        assertNull(tampered.evidences().getFirst().quote());
        assertEquals(SupportStatus.INSUFFICIENT, tampered.claim().supportStatus());
        var shortHit = new RetrievedChunk("c","d","index.pdf","建议每月巡检。",null,null,null,0,0.9);
        var unseen = verifier.verify("C1","巡检周期",List.of(new ClaimVerifier.Citation(1, "巡检周期")),
                List.of(shortHit),Set.of("d"));
        assertEquals(SourceStatus.QUOTE_MISMATCH, unseen.evidences().getFirst().sourceStatus());
        verifyNoInteractions(judge);
    }

    @Test void absentCrossDocumentAndInventedReferencesFailClosed() {
        when(chunks.findById("c")).thenReturn(Optional.of(persisted("other")), Optional.empty());
        for (int i=0; i<2; i++) {
            var evidence = verify(1, "建议每月巡检。").evidences().getFirst();
            assertEquals(SourceStatus.SOURCE_UNAVAILABLE, evidence.sourceStatus());
            assertNull(evidence.quote());
        }
        assertTrue(verify(99, original).claim().evidenceIds().isEmpty());
        assertTrue(verifier.verify("id","未选文档",List.of(new ClaimVerifier.Citation(1,original)),
                List.of(hit),Set.of("other")).claim().evidenceIds().isEmpty());
        verifyNoInteractions(judge);
    }

    @Test void modelFailurePreservesLocatedQuoteAndInsufficientJudgment() {
        when(chunks.findById("c")).thenReturn(Optional.of(persisted("d")));
        when(judge.judge(anyString(), anyList())).thenThrow(new IllegalStateException("timeout"));
        var verified = verify(1, "建议每月巡检。");
        assertEquals(SourceStatus.MATCHED, verified.evidences().getFirst().sourceStatus());
        assertEquals(SupportStatus.INSUFFICIENT, verified.claim().supportStatus());
        assertTrue(verified.claim().reason().contains("判断未完成"));
    }

    @Test void rejectsQuoteCuttingOffLeadingDigit() {
        when(chunks.findById("c")).thenReturn(Optional.of(persisted("d")));
        assertEquals(SourceStatus.QUOTE_MISMATCH, verify(1, "0 天。").evidences().getFirst().sourceStatus());
        verifyNoInteractions(judge);
    }

    @Test void rejectsWrongRank() {
        String table = """
                得分 排名
                中国 美国 德国 中国 美国 德国
                领域汇总 23.10 10.23 5.11 1 2 3
                """;
        var claim = check("中国的研究前沿热度指数为23.10分，排名第二。",
                table, "领域汇总 23.10");
        assertEquals(SourceStatus.MATCHED, claim.evidences().getFirst().sourceStatus());
        assertEquals(SupportStatus.INSUFFICIENT, claim.claim().supportStatus());
        assertEquals(SupportStatus.INSUFFICIENT,
                check("中国在数学领域位居榜首。", "中国在数学领域排名第二。", "中国在数学领域排名第二。")
                        .claim().supportStatus());
    }

    @Test void alignsParallelCountriesAndRanksInProse() {
        String source = "德国、英国和法国的得分分别为9.09、8.71和7.08，排名第三、第四和第五。";
        assertEquals(SupportStatus.SUPPORTED, check(source, source, source).claim().supportStatus());
        assertEquals(SupportStatus.SUPPORTED, check("德国得分9.09分，位列第三。", source, source).claim().supportStatus());
        assertEquals(SupportStatus.INSUFFICIENT, check("法国得分9.09分。", source, source).claim().supportStatus());
        assertEquals(SupportStatus.INSUFFICIENT, check("法国排名第三。", source, source).claim().supportStatus());
    }

    @Test void rejectsWrongTableColumn() {
        String table = """
                得分 排名
                美国中国德国美国中国德国
                国家/地区研究前沿热度指数16.95 15.62 9.09 123
                国家/地区影响度8.46 7.22 4.77 123
                """;
        String row = "国家/地区影响度8.46 7.22 4.77 123";
        assertEquals(SupportStatus.SUPPORTED,
                check("中国的国家/地区影响度为7.22分，排名第二。", table, row).claim().supportStatus());
        assertEquals(SupportStatus.INSUFFICIENT,
                check("美国的国家/地区影响度为7.22分。", table, row).claim().supportStatus());
        assertEquals(SupportStatus.INSUFFICIENT,
                check("中国的研究前沿热度指数为7.22分。", table, row).claim().supportStatus());
        assertEquals(SupportStatus.INSUFFICIENT,
                check("中国的国家/地区影响度为7.22分，排名第三。", table, row).claim().supportStatus());
        assertEquals(SupportStatus.INSUFFICIENT,
                check("中国的国家/地区影响度为7.22分。",
                        row, row).claim().supportStatus());
    }

    @Test void rejectsWrongObject() {
        assertEquals(SupportStatus.INSUFFICIENT,
                check("K22 的巡检周期为30天。", "K21 的巡检周期为30天。", "K21 的巡检周期为30天。").claim().supportStatus());
    }

    @Test void rejectsWrongUnit() {
        assertEquals(SupportStatus.INSUFFICIENT,
                check("K21 的巡检周期为30天。", "K21 的巡检周期为30小时。", "K21 的巡检周期为30小时。").claim().supportStatus());
    }

    @Test void rejectsRecommendationAsRequirement() {
        assertEquals(SupportStatus.INSUFFICIENT,
                check("必须每月巡检。", "建议每月巡检。", "建议每月巡检。").claim().supportStatus());
    }

    @Test void supportsExactCurrencyEquivalenceInTheSamePeriodAndMetric() {
        assertEquals(SupportStatus.SUPPORTED,
                check("2025年甲项目预算为1000000元。", "甲项目2025年预算100万元。", "甲项目2025年预算100万元。")
                        .claim().supportStatus());
    }

    private ClaimVerifier.VerifiedClaim check(String claim, String source, String quote) {
        var row = persisted("d");
        row.setContent(source);
        when(chunks.findById("c")).thenReturn(Optional.of(row));
        when(judge.judge(anyString(), anyList())).thenReturn(
                new ClaimSupportJudge.Judgment(SupportStatus.SUPPORTED, "模型判断支持。"));
        var selected = new RetrievedChunk("c", "d", "index.pdf", source, null, 5, 5, 0, 0.99);
        return verifier.verifyStrict("C1", claim, List.of(new ClaimVerifier.Citation(1, quote)),
                List.of(selected), Set.of("d"));
    }
}
