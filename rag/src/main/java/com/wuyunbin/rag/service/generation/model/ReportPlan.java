package com.wuyunbin.rag.service.generation.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 报告生成计划（大纲）：一个标题 + 有序的章节计划列表。
 *
 * <p>由 {@code OutlineGenerator} 基于用户要求与指定文档元信息，通过 Spring AI
 * 结构化输出（{@code .entity(ReportPlan.class)}）得到，避免手工解析 LLM 的自由文本。</p>
 */
public record ReportPlan(

        String title,

        List<ReportSectionPlan> sections
) {

    /**
     * 紧凑构造器：sections 为 null 时归一为空列表，避免后续 NPE。
     */
    public ReportPlan {
        sections = sections == null ? List.of() : new ArrayList<>(sections);
    }

    /**
     * 业务校验：大纲必须有标题、至少一个章节、章节标题非空且不重复。
     *
     * @throws IllegalArgumentException 大纲结构不合法时抛出（由执行层转为任务 FAILED）
     */
    public void validate() {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("大纲标题为空");
        }
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("大纲章节为空");
        }
        Set<String> seen = new HashSet<>();
        for (ReportSectionPlan section : sections) {
            if (section == null || section.title() == null || section.title().isBlank()) {
                throw new IllegalArgumentException("大纲存在标题为空的章节");
            }
            String normalized = section.title().trim();
            if (!seen.add(normalized)) {
                throw new IllegalArgumentException("大纲存在重复章节: " + normalized);
            }
        }
    }
}
