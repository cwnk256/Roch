package com.wuyunbin.rag.cleaning;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 目录区移除器：删除显式目录标题（# 目录 / ## 目录 / 目 录 / CONTENTS / TABLE OF CONTENTS）
 * 之下、直到下一个同级或更高级 Markdown 标题之间的全部内容。
 *
 * <p>仅识别带 Markdown 标题的显式目录区，不做“短行+页码”启发式判断，避免误杀正文列表。</p>
 */
@Component
public class TableOfContentsRemover {

    /**
     * 目录标题行：捕获组 1 为标题的 # 数量（标题级别）。
     */
    private static final Pattern TOC_HEADING = Pattern.compile(
            "^(#{1,6})\\s*(?:目录|目\\s*录|CONTENTS|TABLE\\s+OF\\s+CONTENTS)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 任意 Markdown ATX 标题行：捕获组 1 为 # 数量。matches() 要求整行匹配，末尾补 .*。
     */
    private static final Pattern ANY_HEADING = Pattern.compile("^(#{1,6})\\s+\\S.*$");

    /**
     * 移除文本中的目录区；无目录标题时原文返回。
     */
    public String remove(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<String> kept = new ArrayList<>(lines.length);

        int skipUntilLevel = -1; // >=0 表示正在跳过目录区，遇到级别 <= 该值的标题时结束
        for (String line : lines) {
            if (skipUntilLevel < 0) {
                Matcher toc = TOC_HEADING.matcher(line);
                if (toc.matches()) {
                    skipUntilLevel = toc.group(1).length();
                    continue; // 目录标题行本身也删除
                }
                kept.add(line);
            } else {
                Matcher heading = ANY_HEADING.matcher(line);
                if (heading.matches() && heading.group(1).length() <= skipUntilLevel) {
                    skipUntilLevel = -1;
                    kept.add(line); // 新章节标题保留
                }
                // 目录区内的行一律丢弃
            }
        }
        return String.join("\n", kept);
    }
}
