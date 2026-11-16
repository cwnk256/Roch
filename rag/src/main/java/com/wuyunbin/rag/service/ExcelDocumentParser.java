package com.wuyunbin.rag.service;

import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;

/**
 * Excel (.xlsx/.xls) 文档解析器：将每个 Sheet 转为 Markdown 表格 + 自然语言描述。
 *
 * <p>参考 <a href="https://www.cnblogs.com/aigent/p/19493231">...</a> 的 Excel 处理策略：
 * <ul>
 *   <li>小表格（≤20行）：整个 Sheet 生成一个 Markdown 表格</li>
 *   <li>大表格（>20行）：按每 10 行分块，每块生成一个表格</li>
 *   <li>所有表格均通过 {@link TableTextEnhancer} 追加自然语言描述</li>
 * </ul>
 *
 * <p>每个 Sheet 以 `# Sheet名` 作为 ATX 标题，heading-aware 切片会自动维护章节路径。</p>
 */
@Component
public class ExcelDocumentParser {

    /**
     * 大表格分块阈值
     */
    private static final int LARGE_SHEET_THRESHOLD = 20;

    /**
     * 大表格分块大小
     */
    private static final int CHUNK_SIZE = 10;

    private final TableTextEnhancer tableEnhancer;

    public ExcelDocumentParser(TableTextEnhancer tableEnhancer) {
        this.tableEnhancer = tableEnhancer;
    }

    /**
     * 解析 Excel 输入流为 Markdown 风格的纯文本。
     *
     * @param inputStream Excel 文件输入流（调用方负责关闭）
     * @return 含 ATX 标题和 Markdown 表格的纯文本
     * @throws IOException 文件读取或解析失败
     */
    public String parseToMarkdown(InputStream inputStream) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                String sheetName = sheet.getSheetName();
                if (sheetName == null || sheetName.isBlank()) {
                    sheetName = "Sheet" + (i + 1);
                }
                renderSheet(sheet, sheetName, sb);
            }
            String result = sb.toString().strip();
            if (result.isEmpty()) {
                throw new IOException("Excel 文档所有 Sheet 均为空");
            }
            return result;
        }
    }

    private void renderSheet(Sheet sheet, String sheetName, StringBuilder sb) {
        int lastRow = sheet.getLastRowNum();
        if (lastRow < 0) {
            return;
        }

        // Sheet 名作为标题
        appendBlock(sb, "# " + sheetName);

        // 读取所有行数据
        int maxCols = 0;
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            int cols = row.getLastCellNum();
            if (cols > maxCols) {
                maxCols = cols;
            }
            String[] cells = new String[cols];
            for (int c = 0; c < cols; c++) {
                cells[c] = getCellValueAsString(row.getCell(c));
            }
            rows.add(cells);
        }

        if (rows.isEmpty()) {
            return;
        }

        // 小表格：整体处理
        if (rows.size() <= LARGE_SHEET_THRESHOLD) {
            String table = buildMarkdownTable(rows, maxCols);
            appendBlock(sb, tableEnhancer.enhance(table));
            return;
        }

        // 大表格：分块处理
        for (int start = 0; start < rows.size(); start += CHUNK_SIZE) {
            int end = Math.min(start + CHUNK_SIZE, rows.size());
            java.util.List<String[]> chunk = rows.subList(start, end);

            // 每块包含表头
            java.util.List<String[]> chunkWithHeader = new java.util.ArrayList<>();
            chunkWithHeader.add(rows.get(0)); // 原始表头
            chunkWithHeader.addAll(chunk);

            String table = buildMarkdownTable(chunkWithHeader, maxCols);
            appendBlock(sb, tableEnhancer.enhance(table));
        }
    }

    private String buildMarkdownTable(java.util.List<String[]> rows, int cols) {
        StringBuilder md = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            md.append('|');
            for (int c = 0; c < cols; c++) {
                String cell = c < row.length ? row[c] : "";
                cell = cell.replace('|', '｜').replace('\n', ' ');
                md.append(' ').append(cell).append(" |");
            }
            md.append('\n');

            // 第一行后插入表头分隔行
            if (i == 0) {
                md.append('|');
                for (int c = 0; c < cols; c++) {
                    md.append(" --- |");
                }
                md.append('\n');
            }
        }
        return md.toString().stripTrailing();
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue().strip();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield new SimpleDateFormat("yyyy-MM-dd").format(cell.getDateCellValue());
                }
                double num = cell.getNumericCellValue();
                if (num == (long) num) {
                    yield String.valueOf((long) num);
                }
                yield String.valueOf(num);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue().strip();
                } catch (Exception e) {
                    try {
                        yield String.valueOf(cell.getNumericCellValue());
                    } catch (Exception ignored) {
                        yield cell.getCellFormula();
                    }
                }
            }
            default -> "";
        };
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
