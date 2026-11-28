package com.wuyunbin.rag.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF 文档解析器：提取文本并按字号统计检测标题，转为 ATX Markdown。
 *
 * <p>PDF 没有结构化标题样式（不同于 Word 的 Heading1~6），本解析器通过两步完成标题检测：
 * <ol>
 *   <li>遍历所有文本片段收集字号分布，以出现频率最高的字号作为正文字号</li>
 *   <li>重新遍历，将字号显著大于正文的短行转为 ATX 标题（# ~ ###）</li>
 * </ol>
 *
 * <p>输出的 Markdown 文本可直接进入 CleaningTextSplitter 管线，
 * heading-aware 切片会自动识别 # ~ ###### 并维护章节路径。</p>
 */
@Component
public class PdfDocumentParser {

    /**
     * 标题行最大长度（超过则不当作标题，避免误判段落首行）
     */
    private static final int HEADING_MAX_LENGTH = 80;
    /**
     * 字号比例阈值：≥1.5 → h1，≥1.3 → h2，≥1.15 → h3
     */
    private static final double RATIO_H1 = 1.5;
    private static final double RATIO_H2 = 1.3;
    private static final double RATIO_H3 = 1.15;
    private final TableTextEnhancer tableEnhancer;
    public PdfDocumentParser(TableTextEnhancer tableEnhancer) {
        this.tableEnhancer = tableEnhancer;
    }

    /**
     * 解析 PDF 输入流为 Markdown 风格的纯文本。
     *
     * @param inputStream PDF 文件输入流（调用方负责关闭）
     * @return 含 ATX 标题的纯文本
     * @throws IOException 文件读取或解析失败
     */
    public String parseToMarkdown(InputStream inputStream) throws IOException {
        return String.join("\n\n", parsePages(inputStream).stream().map(PdfPage::content).toList());
    }

    public record TableRow(int page, String caption, String row, List<String> facts) {}

    public record NativeLayout(Map<Integer,String> pages, List<TableRow> rows, Set<Integer> invisibleTextPages) {}

    public List<TableRow> physicalTableRows(InputStream input) throws IOException {
        return nativeLayout(input).rows();
    }

    public NativeLayout nativeLayout(InputStream input) throws IOException {
        try (PDDocument document = Loader.loadPDF(input.readAllBytes())) {
            var layout = new PdfTableLayout();
            layout.getText(document);
            return new NativeLayout(Map.copyOf(layout.pageTexts), List.copyOf(layout.rows),
                    Set.copyOf(layout.invisibleTextPages));
        }
    }

    public record PdfPage(int number, String content) {}

    public List<PdfPage> parsePages(InputStream inputStream) throws IOException {
        var pages = parsePagesAllowEmpty(inputStream);
        if (pages.isEmpty()) throw new IOException("PDF 文档正文内容为空");
        return pages;
    }

    public List<PdfPage> parsePagesAllowEmpty(InputStream inputStream) throws IOException {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            Map<Integer, List<LineInfo>> pages = new LinkedHashMap<>();
            LineCollectingStripper collector = new LineCollectingStripper(pages);
            collector.setSortByPosition(true);
            collector.setAddMoreFormatting(true);
            collector.getText(document);
            float bodySize = determineBodyFontSize(pages.values().stream().flatMap(List::stream).toList());
            List<PdfPage> result = pages.entrySet().stream()
                    .map(page -> new PdfPage(page.getKey(), buildMarkdown(page.getValue(), bodySize)))
                    .filter(page -> !page.content().isBlank())
                    .toList();
            return result;
        }
    }

    /**
     * 统计所有行字号，返回频率最高的作为正文字号。
     * 若无字号信息（如扫描件），返回 0 表示无法检测标题。
     */
    private float determineBodyFontSize(List<LineInfo> lines) {
        Map<Float, Integer> sizeCounts = new HashMap<>();
        for (LineInfo line : lines) {
            if (line.maxFontSize > 0) {
                sizeCounts.merge(line.maxFontSize, 1, Integer::sum);
            }
        }
        return sizeCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0f);
    }

    private String buildMarkdown(List<LineInfo> lines, float bodySize) {
        StringBuilder sb = new StringBuilder();
        boolean inParagraph = false;

        for (LineInfo line : lines) {
            // PDF 字体映射可能产生 NUL，PostgreSQL 文本字段无法存储它。
            String text = line.text.replace("\0", "").strip();

            if (text.isEmpty()) {
                if (inParagraph) {
                    sb.append("\n\n");
                    inParagraph = false;
                }
                continue;
            }

            int headingLevel = detectHeadingLevel(line, text, bodySize);

            if (headingLevel > 0) {
                if (inParagraph) {
                    sb.append("\n\n");
                    inParagraph = false;
                }
                sb.append("#".repeat(headingLevel)).append(" ").append(text).append("\n\n");
            } else {
                if (inParagraph) {
                    sb.append(" ");
                }
                sb.append(text);
                inParagraph = true;
            }
        }

        return sb.toString().strip();
    }

    private int detectHeadingLevel(LineInfo line, String text, float bodySize) {
        if (bodySize <= 0 || line.maxFontSize <= 0) {
            return 0;
        }
        // 超过最大长度的行不当作标题
        if (text.length() > HEADING_MAX_LENGTH) {
            return 0;
        }
        // 以句末标点结尾的行大概率是段落，不是标题
        if (text.matches(".*[\\.;:!?。，；：！？]$")) {
            return 0;
        }

        double ratio = line.maxFontSize / bodySize;
        if (ratio >= RATIO_H1) {
            return 1;
        } else if (ratio >= RATIO_H2) {
            return 2;
        } else if (ratio >= RATIO_H3) {
            return 3;
        }
        return 0;
    }

    /**
     * 单行文本及其最大字号
     */
    static class LineInfo {
        final String text;
        final float maxFontSize;

        LineInfo(String text, float maxFontSize) {
            this.text = text;
            this.maxFontSize = maxFontSize;
        }
    }

    /**
     * 自定义 PDFTextStripper：收集每行文本和该行的最大字号。
     * PDFBox 按行调用 writeString（每行可能多次调用），在 writeLineSeparator 时结算。
     */
    static class LineCollectingStripper extends PDFTextStripper {
        private final Map<Integer, List<LineInfo>> pages;
        private List<LineInfo> lines;
        private final StringBuilder currentLine = new StringBuilder();
        private float currentMaxFontSize = 0;

        LineCollectingStripper(Map<Integer, List<LineInfo>> pages) throws IOException {
            this.pages = pages;
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            super.startPage(page);
            lines = new ArrayList<>();
            pages.put(getCurrentPageNo(), lines);
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            flushLine();
            super.endPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            currentLine.append(text);
            for (TextPosition tp : textPositions) {
                float size = tp.getFontSizeInPt();
                if (size > currentMaxFontSize) {
                    currentMaxFontSize = size;
                }
            }
        }

        @Override
        protected void writeString(String text) {
            // 无 TextPosition 信息时的兜底（纯文本模式）
            currentLine.append(text);
        }

        @Override
        protected void writeLineSeparator() {
            flushLine();
        }

        private void flushLine() {
            if (currentLine.isEmpty()) return;
            lines.add(new LineInfo(currentLine.toString(), currentMaxFontSize));
            currentLine.setLength(0);
            currentMaxFontSize = 0;
        }

        @Override
        protected void writeParagraphSeparator() {
            flushLine();
            lines.add(new LineInfo("", 0));
        }
    }
}
