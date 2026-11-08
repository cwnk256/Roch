package com.wuyunbin.rag.cleaning;

/**
 * 文档内的一个结构化分段，按原文顺序排列。
 *
 * @param type     分段类型
 * @param content  分段原文（CODE 不含围栏行）
 * @param codeLang 代码语言（仅 CODE 段，围栏上未标注时为 null）
 * @param section  分段所属章节上下文（无标题时为 {@link SectionContext#EMPTY}）
 */
public record TextSegment(SegmentType type, String content, String codeLang, SectionContext section) {

    public static TextSegment prose(String content) {
        return prose(content, SectionContext.EMPTY);
    }

    public static TextSegment prose(String content, SectionContext section) {
        return new TextSegment(SegmentType.PROSE, content, null, section);
    }

    public static TextSegment table(String content) {
        return table(content, SectionContext.EMPTY);
    }

    public static TextSegment table(String content, SectionContext section) {
        return new TextSegment(SegmentType.TABLE, content, null, section);
    }

    public static TextSegment code(String content, String codeLang) {
        return code(content, codeLang, SectionContext.EMPTY);
    }

    public static TextSegment code(String content, String codeLang, SectionContext section) {
        return new TextSegment(SegmentType.CODE, content, codeLang, section);
    }
}
