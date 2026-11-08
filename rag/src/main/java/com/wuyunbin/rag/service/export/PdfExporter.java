package com.wuyunbin.rag.service.export;

import com.wuyunbin.rag.service.generation.model.Evidence;
import com.wuyunbin.rag.service.generation.model.GeneratedDocument;
import com.wuyunbin.rag.service.generation.model.GeneratedSection;
import com.wuyunbin.rag.service.task.DocumentTaskProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 PDFBox 3.x 的 PDF 导出器。
 *
 * <p>PDF 固定结构：封面（标题 + 生成时间）→ 摘要 → 目录 → 分章节正文（每章列出来源）→ 参考资料。</p>
 *
 * <p>中文字体通过 {@code rag.document-task.font-path} 从 classpath 或外部路径加载并嵌入，
 * 不硬编码操作系统字体目录；字体字节只加载一次并缓存，PDFBox 保存时自动子集化，
 * 最终 PDF 仅包含实际使用到的字形。字体缺失将直接失败（任务 FAILED），绝不退回无中文
 * glyph 的标准字体导致乱码。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfExporter implements DocumentExporter {

    private static final String PDF_SUFFIX = ".pdf";
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter COVER_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");
    private static final Pattern UNORDERED = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^\\s*(\\d+)[.、)]\\s+(.*)$");
    private static final Pattern HR = Pattern.compile("^\\s*[-*_]{3,}\\s*$");

    private final DocumentTaskProperties properties;
    private final ResourceLoader resourceLoader;

    /**
     * 字体字节缓存（绑定到具体 PDDocument 的 PDType0Font 每次导出新建，但字节只读取一次）。
     */
    private volatile byte[] fontBytesCache;

    /**
     * 落盘文件名主干：只放行安全字符，杜绝路径穿越；fileStem 实际为任务 ID。
     */
    static String sanitizeFileStem(String stem) {
        if (stem == null) {
            return "task";
        }
        String cleaned = stem.replaceAll("[^A-Za-z0-9_-]", "");
        return cleaned.isEmpty() ? "task" : cleaned;
    }

    // ============================== 字体 ==============================

    /**
     * 用户可读文件名：移除 Windows/Unix 非法文件名字符，防止穿越；为空时用类型+时间戳兜底。
     */
    static String sanitizeDisplayName(String title) {
        if (title == null) {
            return "DOCUMENT-" + LocalDateTime.now().format(FILE_TS);
        }
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "").trim();
        cleaned = cleaned.replaceAll("\\s+", " ");
        if (cleaned.isBlank()) {
            return "DOCUMENT-" + LocalDateTime.now().format(FILE_TS);
        }
        return cleaned.length() > 80 ? cleaned.substring(0, 80).trim() : cleaned;
    }

    // ============================== 文件名处理 ==============================

    @Override
    public ExportedFile export(GeneratedDocument document, String fileStem) {
        if (document == null) {
            throw new IllegalArgumentException("待导出文档不能为空");
        }
        String safeStem = sanitizeFileStem(fileStem);
        Path outputDir = Paths.get(properties.getOutputDir(), safeStem);
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建 PDF 输出目录失败: " + outputDir, e);
        }
        Path output = outputDir.resolve(safeStem + PDF_SUFFIX);

        try (PDDocument pdf = new PDDocument();
             TrueTypeFont ttf = new TTFParser().parseEmbedded(new ByteArrayInputStream(loadFontBytes()))) {
            PDType0Font font = PDType0Font.load(pdf, ttf, true);
            Layout layout = new Layout(pdf, font, ttf.getUnicodeCmapLookup());
            layout.writeCover(document);
            layout.writeSummary(document);
            layout.writeTableOfContents(document);
            layout.writeSections(document);
            layout.writeReferences(document);
            layout.close();
            pdf.save(output.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("PDF 生成失败: " + output, e);
        }

        String displayName = sanitizeDisplayName(document.title()) + PDF_SUFFIX;
        log.info("PDF 已生成 path={} displayName={} sections={} references={}",
                output, displayName, document.sections().size(), document.references().size());
        return new ExportedFile(displayName, output, "application/pdf");
    }

    private byte[] loadFontBytes() throws IOException {
        byte[] cached = fontBytesCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (fontBytesCache == null) {
                String location = properties.getFontPath();
                Resource resource = resourceLoader.getResource(location);
                if (!resource.exists()) {
                    throw new IOException("中文字体资源不存在: " + location
                            + "（请配置 rag.document-task.font-path 指向包含中文 glyph 的 TTF 字体）");
                }
                try (InputStream in = resource.getInputStream()) {
                    fontBytesCache = in.readAllBytes();
                }
                log.info("已加载 PDF 中文字体: {} ({} bytes)", location, fontBytesCache.length);
            }
            return fontBytesCache;
        }
    }

    // ============================== PDF 排版游标 ==============================

    /**
     * 内部排版器：维护 A4 页面、中文字体与当前 y 坐标，自动分页与按宽度换行。
     * 每行使用独立 BT/ET 文本块，从而以页面绝对坐标定位，简化分页控制。
     */
    private final class Layout {

        private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
        private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
        private static final float MARGIN_LEFT = 56f;
        private static final float MARGIN_RIGHT = 56f;
        private static final float MARGIN_TOP = 64f;
        private static final float MARGIN_BOTTOM = 60f;
        private static final float USABLE_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT;

        private static final float COVER_TITLE_SIZE = 22f;
        private static final float BLOCK_TITLE_SIZE = 16f;
        private static final float SECTION_TITLE_SIZE = 13.5f;
        private static final float BODY_SIZE = 10.5f;
        private static final float SMALL_SIZE = 9f;

        private final PDDocument pdf;
        private final PDType0Font font;
        private final CmapLookup unicodeCmap;

        private PDPage page;
        private PDPageContentStream cs;
        private float y;

        private Layout(PDDocument pdf, PDType0Font font, CmapLookup unicodeCmap) throws IOException {
            this.pdf = pdf;
            this.font = font;
            this.unicodeCmap = unicodeCmap;
            newPage();
        }

        private static String stripInline(String text) {
            return text
                    .replace("**", "")
                    .replace("__", "")
                    .replace("`", "");
        }

        private void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            cs = new PDPageContentStream(pdf, page);
            y = PAGE_HEIGHT - MARGIN_TOP;
        }

        private void close() throws IOException {
            cs.close();
        }

        /**
         * 当前页剩余高度不足以容纳一行时翻页。
         */
        private void ensureSpace(float lineHeight) throws IOException {
            if (y - lineHeight < MARGIN_BOTTOM) {
                newPage();
            }
        }

        private void verticalGap(float gap) throws IOException {
            y -= gap;
            ensureSpace(0);
        }

        /**
         * 以绝对坐标绘制一行（独立 BT/ET）。
         */
        private void drawLine(String text, float fontSize, float x) throws IOException {
            float lineHeight = fontSize * 1.55f;
            ensureSpace(lineHeight);
            String safe = filterGlyphs(text);
            cs.beginText();
            cs.setFont(font, fontSize);
            cs.newLineAtOffset(x, y);
            cs.showText(safe);
            cs.endText();
            y -= lineHeight;
        }

        /**
         * 单行水平居中。
         */
        private void drawLineCentered(String text, float fontSize) throws IOException {
            float width = textWidth(text, fontSize);
            float x = MARGIN_LEFT + Math.max(0f, (USABLE_WIDTH - width) / 2f);
            drawLine(text, fontSize, x);
        }

        /**
         * 按可用宽度把文本折成多行（逐 code point，支持中英文混排，长英文词在边界断开）。
         */
        private List<String> wrap(String text, float fontSize, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            float currentWidth = 0f;
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (cp == '\n') {
                    lines.add(current.toString());
                    current.setLength(0);
                    currentWidth = 0f;
                    continue;
                }
                String ch = new String(Character.toChars(cp));
                float w = textWidth(ch, fontSize);
                if (currentWidth + w > maxWidth && current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                    currentWidth = 0f;
                    if (cp == ' ') {
                        continue; // 行首不留空格
                    }
                }
                current.append(ch);
                currentWidth += w;
            }
            lines.add(current.toString());
            return lines;
        }

        /**
         * 绘制普通段落（自动换行，左边界 x）。
         */
        private void drawParagraph(String text, float fontSize, float x) throws IOException {
            float maxWidth = PAGE_WIDTH - MARGIN_RIGHT - x;
            for (String line : wrap(text, fontSize, maxWidth)) {
                drawLine(line, fontSize, x);
            }
        }

        private float textWidth(String text, float fontSize) throws IOException {
            return font.getStringWidth(text) / 1000f * fontSize;
        }

        // ---------------------------- 区块 ----------------------------

        /**
         * 去掉字体不支持的字符，避免绘制出 .notdef 空 glyph。
         *
         * <p>注意不能用 {@code PDType0Font.hasGlyph(int)}：该接口的参数是 PDF 内容流字符码，
         * 在 PDFBox 保存前子集化阶段该码即 GID，并非 Unicode 码位；直接传中文码位（如 U+8BBE）
         * 会被误判为缺字。字形覆盖应查询底层 TTF 的 Unicode cmap。</p>
         */
        private String filterGlyphs(String text) {
            StringBuilder sb = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                int count = Character.charCount(cp);
                if (unicodeCmap.getGlyphId(cp) != 0) {
                    sb.appendCodePoint(cp);
                }
                i += count;
            }
            return sb.toString();
        }

        private void writeCover(GeneratedDocument doc) throws IOException {
            y = PAGE_HEIGHT / 2.4f;
            for (String line : wrap(doc.title(), COVER_TITLE_SIZE, USABLE_WIDTH)) {
                drawLineCentered(line, COVER_TITLE_SIZE);
            }
            y -= 18f;
            drawLineCentered("生成时间：" + LocalDateTime.now().format(COVER_TS), BODY_SIZE);
            newPage();
        }

        private void writeSummary(GeneratedDocument doc) throws IOException {
            drawBlockTitle("摘要");
            String summary = doc.summary();
            if (summary == null || summary.isBlank()) {
                drawParagraph("（无摘要）", BODY_SIZE, MARGIN_LEFT);
            } else {
                drawRichText(summary);
            }
            verticalGap(14f);
        }

        private void writeTableOfContents(GeneratedDocument doc) throws IOException {
            drawBlockTitle("目录");
            int index = 1;
            for (GeneratedSection section : doc.sections()) {
                drawParagraph(index + ". " + section.title(), BODY_SIZE, MARGIN_LEFT);
                index++;
            }
            drawParagraph(index + ". 参考资料", BODY_SIZE, MARGIN_LEFT);
            verticalGap(14f);
        }

        private void writeSections(GeneratedDocument doc) throws IOException {
            int index = 1;
            for (GeneratedSection section : doc.sections()) {
                verticalGap(10f);
                drawLine(index + ". " + section.title(), SECTION_TITLE_SIZE, MARGIN_LEFT);
                verticalGap(4f);
                if (section.content() == null || section.content().isBlank()) {
                    drawParagraph("（本章节无内容）", BODY_SIZE, MARGIN_LEFT);
                } else {
                    drawRichText(section.content());
                }
                writeSectionSources(section);
                index++;
            }
        }

        /**
         * 章节末尾以小字列出本章节引用的文件名（去重，不伪造页码）。
         */
        private void writeSectionSources(GeneratedSection section) throws IOException {
            if (section.evidences() == null || section.evidences().isEmpty()) {
                return;
            }
            Set<String> names = new LinkedHashSet<>();
            for (Evidence evidence : section.evidences()) {
                if (evidence.fileName() != null && !evidence.fileName().isBlank()) {
                    names.add(evidence.fileName());
                }
            }
            if (names.isEmpty()) {
                return;
            }
            verticalGap(3f);
            drawParagraph("来源：" + String.join("；", names), SMALL_SIZE, MARGIN_LEFT);
            verticalGap(4f);
        }

        private void writeReferences(GeneratedDocument doc) throws IOException {
            newPage();
            drawBlockTitle("参考资料");
            List<Evidence> references = doc.references();
            if (references.isEmpty()) {
                drawParagraph("（本文档未引用外部资料）", BODY_SIZE, MARGIN_LEFT);
                return;
            }
            int index = 1;
            for (Evidence evidence : references) {
                String name = evidence.fileName() == null ? evidence.documentId() : evidence.fileName();
                StringBuilder line = new StringBuilder("[").append(index++).append("] ").append(name);
                // 页码仅在存在时追加；当前 Phase 1 不写入页码，保持不补造
                if (evidence.pageStart() != null) {
                    line.append("，第 ").append(evidence.pageStart());
                    if (evidence.pageEnd() != null && !evidence.pageEnd().equals(evidence.pageStart())) {
                        line.append('-').append(evidence.pageEnd());
                    }
                    line.append(" 页");
                }
                drawParagraph(line.toString(), BODY_SIZE, MARGIN_LEFT);
            }
        }

        private void drawBlockTitle(String title) throws IOException {
            drawLine(title, BLOCK_TITLE_SIZE, MARGIN_LEFT);
            verticalGap(6f);
        }

        /**
         * 轻量 Markdown 渲染：支持 # 标题、- / 1. 列表、空行分段，并剥离星号与反引号等行内标记；
         * 表格行与水平线按普通文本或跳过处理（第一版不实现复杂表格排版）。
         */
        private void drawRichText(String markdown) throws IOException {
            String[] rawLines = markdown.replace("\r\n", "\n").split("\n", -1);
            for (String raw : rawLines) {
                String line = raw.strip();

                if (line.isEmpty()) {
                    verticalGap(6f);
                    continue;
                }
                if (HR.matcher(line).matches()) {
                    continue;
                }

                Matcher heading = HEADING.matcher(line);
                if (heading.matches()) {
                    verticalGap(4f);
                    drawParagraph(stripInline(heading.group(1)), SECTION_TITLE_SIZE, MARGIN_LEFT);
                    verticalGap(2f);
                    continue;
                }

                Matcher unordered = UNORDERED.matcher(line);
                if (unordered.matches()) {
                    drawListItem("• ", stripInline(unordered.group(1)));
                    continue;
                }

                Matcher ordered = ORDERED.matcher(line);
                if (ordered.matches()) {
                    drawListItem(ordered.group(1) + ". ", stripInline(ordered.group(2)));
                    continue;
                }

                drawParagraph(stripInline(line), BODY_SIZE, MARGIN_LEFT);
            }
        }

        /**
         * 列表项：首行带前缀，续行悬挂缩进对齐。
         */
        private void drawListItem(String prefix, String text) throws IOException {
            float indent = MARGIN_LEFT + 16f;
            List<String> wrapped = wrap(text, BODY_SIZE, PAGE_WIDTH - MARGIN_RIGHT - indent);
            if (wrapped.isEmpty()) {
                drawLine(prefix, BODY_SIZE, MARGIN_LEFT);
                return;
            }
            drawLine(prefix + wrapped.get(0), BODY_SIZE, MARGIN_LEFT);
            for (int i = 1; i < wrapped.size(); i++) {
                drawLine(wrapped.get(i), BODY_SIZE, indent);
            }
        }
    }
}
