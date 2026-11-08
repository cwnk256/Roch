package com.wuyunbin.rag.service;

import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word (.docx) 文档解析器：提取段落与表格，标题样式转为 ATX Markdown 标题。
 *
 * <p>输出的 Markdown 文本可直接进入 CleaningTextSplitter 管线，
 * heading-aware 切片会自动识别 # ~ ###### 并维护章节路径。</p>
 *
 * <p>标题检测策略：优先匹配 Word 内置标题样式 ID（Heading1~Heading6、
 * 中文版"标题 1"~"标题 6"），其次检查段落的大纲级别（outline level）。</p>
 */
@Component
public class WordDocumentParser {

    /**
     * 匹配 Heading1~Heading6、heading1~heading6、标题1~标题6 等样式 ID
     */
    private static final Pattern HEADING_STYLE = Pattern.compile(
            "(?i)heading[\\s_-]?([1-6])|标题\\s*([1-6])");
    /**
     * 最大 Markdown 标题级别
     */
    private static final int MAX_HEADING_LEVEL = 6;
    private final TableTextEnhancer tableEnhancer;

    public WordDocumentParser(TableTextEnhancer tableEnhancer) {
        this.tableEnhancer = tableEnhancer;
    }

    /**
     * 解析 .docx 输入流为 Markdown 风格的纯文本。
     *
     * @param inputStream .docx 文件输入流（调用方负责关闭）
     * @return 含 ATX 标题和 Markdown 表格的纯文本
     * @throws IOException 文件读取或解析失败
     */
    public String parseToMarkdown(InputStream inputStream) throws IOException {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            StringBuilder sb = new StringBuilder();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    renderParagraph(paragraph, sb);
                } else if (element instanceof XWPFTable table) {
                    renderTable(table, sb);
                }
            }
            String result = sb.toString().strip();
            if (result.isEmpty()) {
                throw new IOException("Word 文档正文内容为空");
            }
            return result;
        }
    }

    private void renderParagraph(XWPFParagraph paragraph, StringBuilder sb) {
        String text = paragraph.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        text = text.strip();

        int headingLevel = detectHeadingLevel(paragraph);
        if (headingLevel > 0) {
            appendBlock(sb, "#".repeat(headingLevel) + " " + text);
        } else {
            appendBlock(sb, text);
        }
    }

    /**
     * 检测段落标题级别，返回 1~6；非标题返回 0。
     *
     * <p>检测顺序：内置标题样式 ID → 大纲级别（部分文档不使用标准样式但设了 outline level）。</p>
     */
    private int detectHeadingLevel(XWPFParagraph paragraph) {
        // 1. 样式 ID 匹配
        String styleId = paragraph.getStyleID();
        if (styleId != null && !styleId.isBlank()) {
            Matcher m = HEADING_STYLE.matcher(styleId);
            if (m.find()) {
                String g1 = m.group(1);
                String g2 = m.group(2);
                if (g1 != null) {
                    return Integer.parseInt(g1);
                }
                if (g2 != null) {
                    return Integer.parseInt(g2);
                }
            }
        }

        // 2. 大纲级别（0=一级标题，1=二级，...，-1=非标题）
        // POI 暂未直接暴露 outline level，通过 CTPPr 间接获取
        try {
            var pPr = paragraph.getCTP().getPPr();
            if (pPr != null && pPr.isSetOutlineLvl()) {
                int outlineLevel = pPr.getOutlineLvl().getVal().intValue();
                if (outlineLevel >= 0 && outlineLevel < MAX_HEADING_LEVEL) {
                    return outlineLevel + 1;
                }
            }
        } catch (Exception ignored) {
            // 安全降级：不识别为标题
        }

        return 0;
    }

    private void renderTable(XWPFTable table, StringBuilder sb) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return;
        }

        StringBuilder md = new StringBuilder();
        boolean headerWritten = false;
        int colCount = 0;

        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            List<XWPFTableCell> cells = row.getTableCells();
            if (cells.isEmpty()) {
                continue;
            }

            md.append('|');
            for (XWPFTableCell cell : cells) {
                String cellText = cell.getText() == null ? "" : cell.getText().strip();
                cellText = cellText.replace('|', '｜').replace('\n', ' ');
                md.append(' ').append(cellText).append(" |");
                if (i == 0) {
                    colCount++;
                }
            }
            md.append('\n');

            // 第一行后插入 Markdown 表头分隔行
            if (!headerWritten) {
                md.append('|');
                for (int c = 0; c < colCount; c++) {
                    md.append(" --- |");
                }
                md.append('\n');
                headerWritten = true;
            }
        }

        if (md.length() > 0) {
            String tableMarkdown = md.toString().stripTrailing();
            appendBlock(sb, tableEnhancer.enhance(tableMarkdown));
        }
    }

    private void appendBlock(StringBuilder sb, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!sb.isEmpty() && !sb.toString().endsWith("\n\n")) {
            if (sb.charAt(sb.length() - 1) == '\n') {
                sb.append('\n');
            } else {
                sb.append("\n\n");
            }
        }
        sb.append(content.strip()).append("\n\n");
    }
}
