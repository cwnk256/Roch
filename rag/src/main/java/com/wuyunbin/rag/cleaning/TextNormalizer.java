package com.wuyunbin.rag.cleaning;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本归一化器：把普通正文/表格单元格中的英文字母小写化以提升检索召回，
 * 但保护 URL（路径大小写敏感）与代码块（标识符大小写敏感）。
 */
@Component
public class TextNormalizer {

    /**
     * http(s) 链接或 www. 开头的网址，整段保持原大小写。
     */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://\\S+|www\\.\\S+", Pattern.CASE_INSENSITIVE);

    /**
     * 普通正文小写化：A-Z 转 a-z，URL 保持原样。
     */
    public String normalizeProse(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        Matcher matcher = URL_PATTERN.matcher(text);
        int last = 0;
        while (matcher.find()) {
            out.append(lowerLatin(text.substring(last, matcher.start())));
            out.append(matcher.group());
            last = matcher.end();
        }
        out.append(lowerLatin(text.substring(last)));
        return out.toString();
    }

    /**
     * Markdown 表格小写化：逐行按 | 拆分，只对单元格内容归一，分隔行（| --- |）无字母不受影响。
     */
    public String normalizeTable(String table) {
        if (table == null || table.isEmpty()) {
            return table;
        }
        String[] lines = table.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String[] cells = lines[i].split("\\|", -1);
            for (int c = 0; c < cells.length; c++) {
                cells[c] = normalizeProse(cells[c]);
            }
            lines[i] = String.join("|", cells);
        }
        return String.join("\n", lines);
    }

    /**
     * 仅把 ASCII 大写字母转小写，不动其他字符（中文标点等不受影响）。
     */
    private String lowerLatin(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            sb.append(ch >= 'A' && ch <= 'Z' ? (char) (ch + 32) : ch);
        }
        return sb.toString();
    }
}
