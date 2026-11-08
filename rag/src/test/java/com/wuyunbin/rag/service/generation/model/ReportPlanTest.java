package com.wuyunbin.rag.service.generation.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReportPlan 业务校验单测：标题非空、至少一章、章节标题非空且不重复。
 */
class ReportPlanTest {

    private ReportSectionPlan section(String title) {
        return new ReportSectionPlan(title, "目的：" + title);
    }

    @Test
    void validate_validPlan_passes() {
        ReportPlan plan = new ReportPlan("设备维护报告",
                List.of(section("第一章 概述"), section("第二章 维护周期")));
        assertDoesNotThrow(plan::validate);
    }

    @Test
    void validate_nullOrBlankTitle_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReportPlan(null, List.of(section("一"))).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new ReportPlan("  ", List.of(section("一"))).validate());
    }

    @Test
    void validate_emptySections_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ReportPlan("标题", List.of()).validate());
        assertTrue(ex.getMessage().contains("章节为空"));
    }

    @Test
    void validate_nullSectionsNormalizedToEmpty_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReportPlan("标题", null).validate());
    }

    @Test
    void validate_nullSectionOrBlankSectionTitle_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReportPlan("标题", java.util.Arrays.asList(section("一"), null)).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new ReportPlan("标题", List.of(section("   "))).validate());
    }

    @Test
    void validate_duplicateSectionTitles_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ReportPlan("标题",
                        List.of(section("第一章 概述"), section("第一章 概述"))).validate());
        assertTrue(ex.getMessage().contains("重复章节"));
    }

    @Test
    void validate_duplicateAfterTrim_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReportPlan("标题",
                        List.of(section("第一章"), section("  第一章  "))).validate());
    }
}
