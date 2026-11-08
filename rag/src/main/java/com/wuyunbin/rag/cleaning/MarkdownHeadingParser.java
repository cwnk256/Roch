package com.wuyunbin.rag.cleaning;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown ATX 标题解析器：单行匹配 {@code #} 到 {@code ######} 标题。
 *
 * <p>规则：井号 1~6 个，其后必须至少一个空白；标题尾部装饰性井号被移除，
 * 标题正文内部的井号保留。无状态，线程安全。</p>
 */
public class MarkdownHeadingParser {

    /**
     * 组 1：井号序列；组 2：标题文本（尾部空格与装饰井号在组外消费）。
     */
    private static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    /**
     * 解析单行文本；非标题行返回 empty。
     */
    public Optional<Heading> parse(String line) {
        if (line == null) {
            return Optional.empty();
        }
        Matcher matcher = ATX_HEADING.matcher(line);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int level = matcher.group(1).length();
        String title = matcher.group(2).trim();
        if (title.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Heading(level, title));
    }
}
