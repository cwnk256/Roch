package com.wuyunbin.rag.cleaning;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownHeadingParserTest {

    private final MarkdownHeadingParser parser = new MarkdownHeadingParser();

    @Test
    void parsesAtxHeadings() {
        Optional<Heading> h1 = parser.parse("# 一级标题");
        assertTrue(h1.isPresent());
        assertEquals(1, h1.get().level());
        assertEquals("一级标题", h1.get().title());

        Optional<Heading> h3 = parser.parse("### 三级标题 ###");
        assertTrue(h3.isPresent());
        assertEquals(3, h3.get().level());
        assertEquals("三级标题", h3.get().title());
    }

    @Test
    void rejectsNonHeadingLines() {
        // 级别超过 6 级
        assertFalse(parser.parse("####### 非标题").isPresent());
        // 井号后缺少空格
        assertFalse(parser.parse("#紧贴文字").isPresent());
        // 普通正文
        assertFalse(parser.parse("普通正文").isPresent());
        // 只有井号没有文本
        assertFalse(parser.parse("#").isPresent());
    }

    @Test
    void keepsInternalHashInTitle() {
        Optional<Heading> heading = parser.parse("## C# 教程 ##");
        assertTrue(heading.isPresent());
        assertEquals(2, heading.get().level());
        assertEquals("C# 教程", heading.get().title());
    }

    @Test
    void buildsNestedSectionPath() {
        List<Heading> stack = List.of(
                new Heading(1, "学校"),
                new Heading(2, "宿舍"),
                new Heading(3, "熄灯"));

        SectionContext context = SectionContext.from(stack);

        assertEquals("学校 / 宿舍 / 熄灯", context.sectionPath());
        assertEquals(3, context.sectionLevel());
        assertEquals(64, context.sectionId().length(), "section_id 应为 64 位 SHA-256 十六进制");
    }

    @Test
    void samePathProducesSameSectionId() {
        SectionContext first = SectionContext.from(List.of(new Heading(1, "学校"), new Heading(2, "宿舍")));
        SectionContext second = SectionContext.from(List.of(new Heading(1, "学校"), new Heading(2, "宿舍")));
        SectionContext other = SectionContext.from(List.of(new Heading(1, "学校"), new Heading(2, "食堂")));

        assertEquals(first.sectionId(), second.sectionId());
        assertFalse(first.sectionId().equals(other.sectionId()));
    }

    @Test
    void emptyStackReturnsEmptyContext() {
        SectionContext context = SectionContext.from(List.of());
        assertEquals(SectionContext.EMPTY, context);
        assertEquals("", context.sectionId());
        assertEquals("", context.sectionPath());
        assertEquals(0, context.sectionLevel());
    }
}
