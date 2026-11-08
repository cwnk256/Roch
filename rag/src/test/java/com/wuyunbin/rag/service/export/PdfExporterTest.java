package com.wuyunbin.rag.service.export;

import com.wuyunbin.rag.service.generation.model.Evidence;
import com.wuyunbin.rag.service.generation.model.GeneratedDocument;
import com.wuyunbin.rag.service.generation.model.GeneratedSection;
import com.wuyunbin.rag.service.task.DocumentTaskProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
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
}
