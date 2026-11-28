package com.wuyunbin.rag.service.export;

import com.wuyunbin.rag.service.generation.model.Claim;
import com.wuyunbin.rag.service.generation.model.SourceStatus;
import com.wuyunbin.rag.service.generation.model.SupportStatus;
import com.wuyunbin.rag.dto.ReportExportOptions;
import com.wuyunbin.rag.dto.ReportConfiguration;
import com.wuyunbin.rag.service.comparison.ComparisonResult;
import com.wuyunbin.rag.service.generation.model.Evidence;
import com.wuyunbin.rag.service.generation.model.GeneratedDocument;
import com.wuyunbin.rag.service.generation.model.GeneratedSection;
import com.wuyunbin.rag.service.task.DocumentTaskProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PdfExporter 单测：使用随包分发的真实中文字体子集，验证
 * 落盘文件存在、可被 PDFBox 重新打开、页数大于 0、抽取文本含中文且无乱码；
 * 以及字体缺失时直接失败（绝不退回无中文 glyph 的标准字体）。
 */
class PdfExporterTest {

    @TempDir
    Path tempDir;

    private PdfExporter exporter;

    private static void assertContains(String text, String fragment) {
        assertTrue(text.contains(fragment),
                "PDF 文本应包含 [" + fragment + "]，实际抽取文本：\n" + text);
    }

    @BeforeEach
    void setUp() {
        DocumentTaskProperties properties = new DocumentTaskProperties();
        properties.setOutputDir(tempDir.toString());
        properties.setFontPath("classpath:fonts/NotoSansSC-Regular.ttf");
        exporter = new PdfExporter(properties, new DefaultResourceLoader());
    }

    private GeneratedDocument sampleDocument() {
        Evidence withPage = new Evidence("docA", "设备维护手册.md", "cA1",
                "第一章 概述", 1, 2, 0.91);
        Evidence withoutPage = new Evidence("docB", "安全规程.pdf", "cB1",
                "第二章 安全事项", null, null, 0.86);

        GeneratedSection s1 = new GeneratedSection("第一章 概述", """
                # 设备总体情况
                
                本章概述设备日常维护要点，正文用于验证中文排版与自动换行是否正常。
                
                - 日常检查项目包括清洁、紧固与润滑。
                - 发现异常应立即停机并上报。
                
                1. 每日检查一次运行状态。
                2. 每周复核关键部件磨损。
                """, List.of(withPage));
        GeneratedSection s2 = new GeneratedSection("第二章 安全事项",
                "安全操作必须遵守规程：佩戴防护装备、断电后检修，并保留维护记录。",
                List.of(withoutPage));

        return new GeneratedDocument(
                "设备维护总结报告",
                "本文档汇总设备维护与安全管理要点，用于验证摘要区块渲染。",
                List.of(s1, s2),
                List.of(withPage, withoutPage));
    }

    @Test
    void export_chineseDocument_writesValidReadablePdf() throws Exception {
        GeneratedDocument document = sampleDocument();

        ExportedFile exported = exporter.export(document, "task-abc123");

        // 落盘文件名按 taskId 消毒，展示名按标题消毒
        Path expected = tempDir.resolve("task-abc123").resolve("task-abc123.pdf");
        assertTrue(Files.exists(expected), "PDF 文件应落盘到输出目录");
        assertTrue(Files.size(expected) > 0);
        assertEquals(expected, exported.path());
        assertEquals("设备维护总结报告.pdf", exported.fileName());
        assertEquals("application/pdf", exported.contentType());

        // 用 PDFBox 重新打开：页数 > 0，文本可抽取
        try (PDDocument reopened = Loader.loadPDF(expected.toFile())) {
            assertTrue(reopened.getNumberOfPages() > 0, "PDF 应至少包含一页");

            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(reopened);

            // 各区块中文均应可读（无乱码），字体子集嵌入生效
            assertContains(text, "设备维护总结报告");   // 封面 + 目录
            assertContains(text, "本文档汇总设备维护");   // 摘要
            assertContains(text, "目录");
            assertContains(text, "第一章 概述");
            assertContains(text, "第二章 安全事项");
            assertContains(text, "日常检查项目");
            assertContains(text, "参考资料");
            assertContains(text, "设备维护手册.md");
            assertContains(text, "安全规程.pdf");
            // 有页码的证据输出页码；无页码证据不伪造
            assertContains(text, "第 1-2 页");
        }
    }

    @Test
    void reExportUsesSameSnapshotsWithEitherCitationStyle() throws Exception {
        Evidence source = new Evidence("docA", "设备维护手册.pdf", "chunk-1", null, 7, 7, .8,
                "ev-12", "原文校准周期为30天。", SourceStatus.MATCHED, "parser-1", "sha256", 0, null, "页内字符 [16,31)");
        Claim claim = new Claim("cl-1", "校准周期为30天。", List.of("ev-12"), SupportStatus.SUPPORTED, "引文明确", true);
        Claim gap = Claim.unsupported("cl-2", "预计成本降低。", "未提供预测依据");
        Evidence mismatch = new Evidence("docA", "设备维护手册.pdf", "chunk-2", null, null, null, .4,
                "ev-13", null, SourceStatus.QUOTE_MISMATCH, "parser-1", "sha256", 1, "虚构引文");
        Evidence unavailable = new Evidence("docA", "设备维护手册.pdf", "missing", null, null, null, .3,
                "ev-14", null, SourceStatus.SOURCE_UNAVAILABLE, null, null, null, null);
        Claim failed = new Claim("cl-3", "需复核的陈述。", List.of("ev-13", "ev-14"),
                SupportStatus.INSUFFICIENT, "引文或来源未匹配", true);
        var evidence = List.of(source, mismatch, unavailable);
        GeneratedSection section = new GeneratedSection("结论", "", evidence, List.of(claim, gap, failed));
        GeneratedDocument doc = new GeneratedDocument("核验报告", claim.text(), List.of(section), List.of(source),
                "v2", List.of("cl-1"), List.of(), evidence, null, null, java.util.Map.of());
        String numbered = pdfText(exporter.export(doc, "numbered", null));
        String numberedWithoutAppendix = pdfText(exporter.export(doc, "numbered-no-appendix", new ReportExportOptions(
                ReportExportOptions.CitationStyle.NUMBERED, false, false)));
        String named = pdfText(exporter.export(doc, "named", new ReportExportOptions(
                ReportExportOptions.CitationStyle.FILE_LOCATION, false, false)));
        for (String required : List.of("校准周期为30天。", "原文已匹配", "支持", "预计成本降低。", "证据不足", "未提供预测依据", "引文不匹配", "来源不可用")) {
            assertContains(numbered, required);
            assertContains(named, required);
        }
        assertContains(numbered, "[1]");
        assertContains(numberedWithoutAppendix, "[1] 设备维护手册.pdf · 第 7 页");
        assertFalse(numberedWithoutAppendix.contains("证据附录"));
        assertContains(numbered, "原文校准周期为30天。");
        assertContains(numbered, "目录");
        assertContains(named, "[设备维护手册.pdf · 第 7 页 · 页内字符 [16,31)]");
        assertFalse(named.contains("目录"));
        assertFalse(named.contains("证据附录"));
        assertFalse(named.contains("原文校准周期为30天。"));
    }

    @Test
    void comparisonExportPreservesSelectedOrderBothSourcesAndMissingCells() throws Exception {
        Evidence first = new Evidence("a", "A.pdf", "ca", "附件", 1, 1, .9, "ea",
                "周期为30天", SourceStatus.MATCHED, "1", "ha", 0, null);
        Evidence target = new Evidence("a", "A.pdf", "ca", "附件", 1, 1, .9, "et",
                "目标为60天", SourceStatus.MATCHED, "1", "ha", 0, null);
        Evidence second = new Evidence("b", "B.pdf", "cb", null, 4, 4, .8, "eb",
                "周期为60天", SourceStatus.MATCHED, "1", "hb", 0, null);
        List<Claim> claims = List.of(new Claim("A", first.quote(), List.of("ea"), SupportStatus.SUPPORTED, "明确", true),
                new Claim("B", second.quote(), List.of("eb"), SupportStatus.SUPPORTED, "明确", true),
                new Claim("T", target.quote(), List.of("et"), SupportStatus.SUPPORTED, "明确", true));
        var fixed = new ComparisonResult.Fact("A", null, null, null, "周期", "30", "天",
                "720", "小时", true, true, false, "固定", first.quote());
        var unverified = new ComparisonResult.Fact("T", null, null, null, "周期", "60", "天",
                null, null, false, false, false, null, target.quote());
        var conflict = new ComparisonResult.Conclusion(ComparisonResult.Kind.POSSIBLE_CONFLICT, "周期",
                "两份规范的固定周期不同。", List.of("A", "B"));
        var compare = new ComparisonResult(List.of(new ComparisonResult.Source("a", "A.pdf"),
                new ComparisonResult.Source("b", "B.pdf")), List.of(new ComparisonResult.Row("费用",
                List.of(new ComparisonResult.Cell("a", ComparisonResult.Status.NOT_FOUND, "本次未找到依据", List.of()),
                        new ComparisonResult.Cell("b", ComparisonResult.Status.RETRIEVAL_FAILED, "检索失败", List.of()))),
                new ComparisonResult.Row("周期", List.of(new ComparisonResult.Cell("a",
                        ComparisonResult.Status.FOUND, "", List.of(fixed, unverified)),
                        new ComparisonResult.Cell("b", ComparisonResult.Status.NOT_FOUND, "", List.of())))),
                List.of(conflict), new ComparisonResult.Metrics(2, 1, 100));
        var sections = List.of(new GeneratedSection("疑似矛盾", conflict.text(), List.of()),
                new GeneratedSection("逐项对比", "", List.of(first, second, target), claims));
        var options = new ReportExportOptions(ReportExportOptions.CitationStyle.FILE_LOCATION, false, false);
        var config = new ReportConfiguration("COMPARISON", "1", "对比", List.of(
                new ReportConfiguration.Section("conflicts", "疑似矛盾", false, ""),
                new ReportConfiguration.Section("matrix", "逐项对比", true, "")), "", options, List.of("费用"), "model", "1");
        var doc = new GeneratedDocument("对比", conflict.text(), sections, List.of(first, second), "v2",
                List.of("A", "B"), List.of(), List.of(first, second, target), compare, config, java.util.Map.of());
        String text = pdfText(exporter.export(doc, "comparison"));
        assertTrue(text.indexOf("1. 疑似矛盾") < text.indexOf("2. 逐项对比"));
        String conflictSection = text.substring(text.indexOf("1. 疑似矛盾"), text.indexOf("2. 逐项对比"));
        assertContains(conflictSection, "两份规范的固定周期不同。");
        assertContains(conflictSection, "[A.pdf · 第 1 页 · 附件] [1]");
        assertContains(conflictSection, "[B.pdf · 第 4 页]");
        assertContains(text, "本次未找到依据");
        assertContains(text, "检索失败");
        String matrix = text.substring(text.indexOf("2. 逐项对比"));
        assertContains(matrix, "换算值：720 小时");
        assertContains(matrix, "比较字段未核验");
        assertContains(matrix, "[A.pdf · 第 1 页 · 附件] [3]");
        assertTrue(matrix.indexOf("[1]") < matrix.indexOf("比较字段未核验"));
        assertTrue(matrix.indexOf("比较字段未核验") < matrix.indexOf("[3]"));
        assertFalse(text.contains("证据附录"));
    }

    private String pdfText(ExportedFile file) throws Exception {
        try (PDDocument document = Loader.loadPDF(file.path().toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    void export_stripsUnsafeCharsFromFileStem() throws Exception {
        ExportedFile exported = exporter.export(sampleDocument(), "../task-恶意路径/xyz");

        // 路径穿越字符被剥离，文件仍只落在输出目录内
        Path output = exported.path();
        assertEquals(tempDir.resolve("task-xyz"), output.getParent());
        assertTrue(Files.exists(output));
        assertTrue(output.getFileName().toString().endsWith(".pdf"));
    }

    @Test
    void export_missingFont_failsFast() {
        DocumentTaskProperties properties = new DocumentTaskProperties();
        properties.setOutputDir(tempDir.toString());
        properties.setFontPath("classpath:fonts/not-exists.ttf");
        PdfExporter failingExporter = new PdfExporter(properties, new DefaultResourceLoader());

        // 字体缺失必须直接失败，由 Service 将任务置为 FAILED，不生成乱码 PDF
        assertThrows(IllegalStateException.class,
                () -> failingExporter.export(sampleDocument(), "task-no-font"));
    }
    @Test
    void export_addsSectionBookmarksPointingToSectionPages() throws Exception {
        var sample = sampleDocument();
        var longSection = new GeneratedSection("第一章 概述", "本段用于分页。\n".repeat(150), List.of());
        var document = new GeneratedDocument(sample.title(), sample.summary(),
                List.of(longSection, sample.sections().get(1)), sample.references());
        Path output = exporter.export(document, "task-bookmarks").path();

        try (PDDocument reopened = Loader.loadPDF(Files.readAllBytes(output))) {
            PDDocumentOutline outline = reopened.getDocumentCatalog().getDocumentOutline();
            assertNotNull(outline, "PDF 应包含文档书签");

            PDOutlineItem firstSection = findBookmark(outline, "1. 第一章 概述");
            PDOutlineItem secondSection = findBookmark(outline, "2. 第二章 安全事项");
            assertNotNull(firstSection);
            assertNotNull(secondSection);
            assertNotNull(firstSection.getDestination());
            assertNotNull(secondSection.getDestination());
            assertTrue(reopened.getPages().indexOf(pageOf(firstSection)) >= 0);
            int secondPage = reopened.getPages().indexOf(pageOf(secondSection));
            assertTrue(secondPage > reopened.getPages().indexOf(pageOf(firstSection)));
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(secondPage + 1);
            stripper.setEndPage(secondPage + 1);
            assertTrue(stripper.getText(reopened).contains("第二章 安全事项"));
        }
    }

    private static PDOutlineItem findBookmark(PDDocumentOutline outline, String title) {
        for (PDOutlineItem item : outline.children()) {
            if (title.equals(item.getTitle())) {
                return item;
            }
        }
        return null;
    }

    private static org.apache.pdfbox.pdmodel.PDPage pageOf(PDOutlineItem item) throws java.io.IOException {
        return ((PDPageDestination) item.getDestination()).getPage();
    }
}
