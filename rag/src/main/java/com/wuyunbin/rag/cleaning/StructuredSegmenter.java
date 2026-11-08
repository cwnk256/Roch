package com.wuyunbin.rag.cleaning;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化分段器：按行扫描，把文档切分为按原文顺序排列的
 * {@link SegmentType#PROSE} / {@link SegmentType#TABLE} / {@link SegmentType#CODE} 段。
 *
 * <ul>
 *   <li>代码块：``` 或 ~~~ 围栏（允许最多 3 个前导空格），围栏内容整体成段，输出时去掉围栏行；</li>
 *   <li>标题：Markdown ATX 标题先结算上一段，再压入标题栈，从结构上形成正文段的硬边界；</li>
 *   <li>表格：连续的 | 起始行，且至少 2 行并包含分隔行（| --- |）才判定为 Markdown 表格；</li>
 *   <li>其余内容归入正文段。</li>
 * </ul>
 * <p>
 * 每个分段都携带生成时的 {@link SectionContext} 快照。
 */
@Component
public class StructuredSegmenter {

    /**
     * 围栏代码块起始行：捕获组 1 为围栏字符（` 或 ~），组 2 为语言标注。
     */
    private static final Pattern FENCE = Pattern.compile(
            "^ {0,3}(`{3,}|~{3,})(.*)$");

    /**
     * Markdown 表格行：以 | 开头并以 | 结尾。
     */
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");

    /**
     * 表格分隔行：只含 |、-、:、空白（且必须含 -）。
     */
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^[\\s|:.-]*-+[\\s|:.-]*$");

    private final MarkdownHeadingParser headingParser = new MarkdownHeadingParser();

    /**
     * 把全文切分为有序分段。
     */
    public List<TextSegment> segment(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        List<TextSegment> segments = new ArrayList<>();
        StringBuilder prose = new StringBuilder();
        List<String> pendingRows = new ArrayList<>();
        List<Heading> headingStack = new ArrayList<>();
        SectionContext currentSection = SectionContext.EMPTY;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                // 代码块开始：围栏优先于标题，先结算正文与待定表格行
                resolvePendingRows(pendingRows, prose, segments, currentSection);
                flushProse(prose, segments, currentSection);

                String fenceChars = fence.group(1);
                char fenceChar = fenceChars.charAt(0);
                int fenceLen = fenceChars.length();
                String lang = fence.group(2).trim();

                List<String> codeLines = new ArrayList<>();
                int j = i + 1;
                for (; j < lines.length; j++) {
                    String codeLine = lines[j];
                    if (isClosingFence(codeLine, fenceChar, fenceLen)) {
                        break;
                    }
                    codeLines.add(codeLine);
                }
                i = j; // 未找到闭合围栏时 j == length，剩余内容全部归入代码块
                segments.add(TextSegment.code(String.join("\n", codeLines).strip(),
                        lang.isEmpty() ? null : lang, currentSection));
                continue;
            }

            Optional<Heading> heading = headingParser.parse(line);
            if (heading.isPresent()) {
                // 必须先 resolve 再 flush：未构成表格的行会被归还到 prose，
                // 需在同一章节上下文内冲刷，避免泄漏到标题之后的新章节。
                resolvePendingRows(pendingRows, prose, segments, currentSection);
                flushProse(prose, segments, currentSection);

                pushHeading(headingStack, heading.get());
                currentSection = SectionContext.from(headingStack);

                // 标题行作为新正文段的起点，使切片自带标题上下文
                prose.append(line).append('\n');
                continue;
            }

            if (TABLE_ROW.matcher(line).matches()) {
                pendingRows.add(line);
            } else {
                resolvePendingRows(pendingRows, prose, segments, currentSection);
                prose.append(line).append('\n');
            }
        }
        resolvePendingRows(pendingRows, prose, segments, currentSection);
        flushProse(prose, segments, currentSection);
        return segments;
    }

    /**
     * 标题入栈：弹出级别大于等于新标题的栈顶标题，再压入新标题。
     */
    private void pushHeading(List<Heading> stack, Heading heading) {
        while (!stack.isEmpty() && stack.get(stack.size() - 1).level() >= heading.level()) {
            stack.remove(stack.size() - 1);
        }
        stack.add(heading);
    }

    /**
     * 判断是否为与开围栏匹配的闭合围栏（同字符、长度不短于开围栏，行内只有围栏与空白）。
     */
    private boolean isClosingFence(String line, char fenceChar, int fenceLen) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != fenceChar) {
            return false;
        }
        int count = 0;
        while (count < trimmed.length() && trimmed.charAt(count) == fenceChar) {
            count++;
        }
        return count >= fenceLen && trimmed.substring(count).isBlank();
    }

    /**
     * 结算连续表格行：构成表格则先冲刷正文再追加 TABLE 段；否则作为普通正文归还。
     */
    private void resolvePendingRows(List<String> pendingRows, StringBuilder prose,
                                    List<TextSegment> segments, SectionContext section) {
        if (pendingRows.isEmpty()) {
            return;
        }
        if (isTable(pendingRows)) {
            flushProse(prose, segments, section);
            segments.add(TextSegment.table(String.join("\n", pendingRows), section));
        } else {
            for (String row : pendingRows) {
                prose.append(row).append('\n');
            }
        }
        pendingRows.clear();
    }

    private boolean isTable(List<String> rows) {
        if (rows.size() < 2) {
            return false;
        }
        for (String row : rows) {
            if (TABLE_SEPARATOR.matcher(row).matches()) {
                return true;
            }
        }
        return false;
    }

    private void flushProse(StringBuilder prose, List<TextSegment> segments, SectionContext section) {
        String content = prose.toString();
        if (!content.isBlank()) {
            segments.add(TextSegment.prose(content.strip(), section));
        }
        prose.setLength(0);
    }
}
