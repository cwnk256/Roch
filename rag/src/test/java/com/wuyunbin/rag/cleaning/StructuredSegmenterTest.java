package com.wuyunbin.rag.cleaning;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StructuredSegmenterTest {

    private static final String DOC = """
            # 第一章
            这是第一章的正文内容，一段普通描述文字。
            ## 表格小节
            | 列A | 列B |
            | --- | --- |
            | 1 | 2 |
            ## 代码小节
            ```java
            # 这不是标题
            int x = 1;
            ```
            ### 深层
            深层正文内容。
            ## 回到二级
            回到二级正文内容。
            """;
    private final StructuredSegmenter segmenter = new StructuredSegmenter();

    @Test
    void attachesNearestHeadingToTableAndCode() {
        List<TextSegment> segments = segmenter.segment(DOC);

        TextSegment table = segments.stream()
                .filter(s -> s.type() == SegmentType.TABLE).findFirst().orElseThrow();
        assertEquals("第一章 / 表格小节", table.section().sectionPath());

        TextSegment code = segments.stream()
                .filter(s -> s.type() == SegmentType.CODE).findFirst().orElseThrow();
        assertEquals("第一章 / 代码小节", code.section().sectionPath());
        // 围栏内的 # 行是代码，不是标题
        assertTrue(code.content().contains("# 这不是标题"));
    }

    @Test
    void separatesProseByHeadingAndMaintainsStack() {
        List<TextSegment> segments = segmenter.segment(DOC);
        List<TextSegment> prose = segments.stream()
                .filter(s -> s.type() == SegmentType.PROSE).toList();

        // 一级标题正文段只属于一级章节，不含二级标题
        TextSegment chapterBody = prose.stream()
                .filter(s -> s.content().contains("这是第一章的正文内容")).findFirst().orElseThrow();
        assertEquals("第一章", chapterBody.section().sectionPath());
        assertFalse(chapterBody.content().contains("表格小节"));

        // 三级标题段：路径完整、级别为 3
        TextSegment deep = prose.stream()
                .filter(s -> s.content().contains("深层正文内容")).findFirst().orElseThrow();
        assertEquals("第一章 / 代码小节 / 深层", deep.section().sectionPath());
        assertEquals(3, deep.section().sectionLevel());

        // 三级标题后再遇二级标题：栈必须弹出深层与代码小节
        TextSegment back = prose.stream()
                .filter(s -> s.content().contains("回到二级正文内容")).findFirst().orElseThrow();
        assertEquals("第一章 / 回到二级", back.section().sectionPath());
        assertEquals(2, back.section().sectionLevel());
    }

    @Test
    void keepsHeadingLineAsStartOfNewProseSegment() {
        List<TextSegment> segments = segmenter.segment(DOC);
        // 每个二级标题都成为新正文段的起点（硬边界）
        assertTrue(segments.stream().anyMatch(s -> s.type() == SegmentType.PROSE
                && s.content().startsWith("## 表格小节")));
        assertTrue(segments.stream().anyMatch(s -> s.type() == SegmentType.PROSE
                && s.content().startsWith("## 回到二级")));
    }

    @Test
    void emptyContextForDocumentWithoutHeadings() {
        List<TextSegment> segments = segmenter.segment("普通文档第一段。\n普通文档第二段。");
        assertFalse(segments.isEmpty());
        for (TextSegment segment : segments) {
            assertEquals(SectionContext.EMPTY, segment.section());
        }
    }
}
