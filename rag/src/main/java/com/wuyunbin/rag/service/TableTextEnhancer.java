package com.wuyunbin.rag.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格文本增强器：将 Markdown 表格转换为自然语言描述，提升 RAG 语义检索质量。
 *
 * <p>参考 https://www.cnblogs.com/aigent/p/19493231 的表格处理策略2：
 * 将表格行转为「{主语}的{列名}是{值}」格式的自然语句，
 * 使向量检索能匹配用户自然语言提问（如「语文多少分及格」→「语文的及格分数是60」）。</p>
 *
 * <p>输出格式：在原始 Markdown 表格后追加自然语言描述段落，
 * 两者共存于同一文本块中，兼顾精确匹配和语义检索。</p>
 */
@Component
public class TableTextEnhancer {

    /**
     * 大表格阈值：超过此行数时额外生成逐行拆分描述
     */
    private static final int LARGE_TABLE_THRESHOLD = 5;

    /**
     * 增强 Markdown 表格文本：在原表格后追加自然语言描述。
     *
     * @param markdownTable Markdown 表格文本（含表头分隔行）
     * @return 增强后的文本（原表格 + 空行 + 自然语言描述）
     */
    public String enhance(String markdownTable) {
        if (markdownTable == null || markdownTable.isBlank()) {
            return markdownTable;
        }

        List<String[]> rows = parseMarkdownTable(markdownTable);
        if (rows.size() < 2) {
            return markdownTable;
        }

        String[] headers = rows.get(0);
        List<String[]> dataRows = rows.subList(1, rows.size());

        StringBuilder sb = new StringBuilder(markdownTable.strip());
        sb.append("\n\n");

        // 整体描述
        sb.append("这是一个包含").append(dataRows.size()).append("行数据的表格，列包括：")
                .append(String.join("、", headers)).append("。\n\n");

        // 逐行转自然语言
        for (String[] row : dataRows) {
            String subject = row.length > 0 ? row[0].strip() : "";
            List<String> sentences = new ArrayList<>();

            for (int col = 1; col < headers.length && col < row.length; col++) {
                String value = row[col].strip();
                if (!value.isEmpty()) {
                    sentences.add(subject + "的" + headers[col] + "是" + value);
                }
            }

            if (!sentences.isEmpty()) {
                sb.append(String.join("；", sentences)).append("。\n");
            }
        }

        // 大表格：追加统计信息
        if (dataRows.size() > LARGE_TABLE_THRESHOLD) {
            sb.append("\n").append(generateStatistics(headers, dataRows));
        }

        return sb.toString().strip();
    }

    /**
     * 解析 Markdown 表格为行列数组。
     *
     * @return 第一行为表头，其余为数据行；每行是单元格数组
     */
    private List<String[]> parseMarkdownTable(String markdown) {
        List<String[]> rows = new ArrayList<>();
        String[] lines = markdown.strip().split("\n");

        for (String line : lines) {
            line = line.strip();
            if (!line.startsWith("|")) {
                continue;
            }
            // 跳过表头分隔行 | --- | --- |
            if (line.matches("^\\|[-\\s:|]+\\|$")) {
                continue;
            }

            String[] cells = line.split("\\|");
            // 去掉首尾空元素（split 会产生）
            List<String> cellList = new ArrayList<>();
            for (String cell : cells) {
                String c = cell.strip();
                if (!c.isEmpty() || !cellList.isEmpty()) {
                    cellList.add(c);
                }
            }
            // 去掉末尾空元素
            while (!cellList.isEmpty() && cellList.get(cellList.size() - 1).isEmpty()) {
                cellList.remove(cellList.size() - 1);
            }

            if (!cellList.isEmpty()) {
                rows.add(cellList.toArray(new String[0]));
            }
        }

        return rows;
    }

    /**
     * 为数值列生成统计信息（最小值/最大值）。
     */
    private String generateStatistics(String[] headers, List<String[]> dataRows) {
        StringBuilder sb = new StringBuilder();

        for (int col = 1; col < headers.length; col++) {
            double min = Double.MAX_VALUE;
            double max = Double.MIN_VALUE;
            boolean hasNumeric = false;

            for (String[] row : dataRows) {
                if (col < row.length) {
                    try {
                        double val = Double.parseDouble(row[col].strip());
                        min = Math.min(min, val);
                        max = Math.max(max, val);
                        hasNumeric = true;
                    } catch (NumberFormatException ignored) {
                        // 非数值列跳过
                    }
                }
            }

            if (hasNumeric && min != Double.MAX_VALUE) {
                if (min == max) {
                    sb.append(headers[col]).append("均为").append(formatNumber(min)).append("；");
                } else {
                    sb.append(headers[col]).append("的范围是")
                            .append(formatNumber(min)).append("到").append(formatNumber(max)).append("；");
                }
            }
        }

        if (sb.length() > 0) {
            return sb.toString();
        }
        return "";
    }

    private String formatNumber(double value) {
        if (value == (long) value) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
