package com.wuyunbin.rag.service.generation.model;

/**
 * 报告大纲中的单个章节计划：标题 + 该章节的生成目的。
 *
 * <p>{@code purpose} 会与标题一起作为章节检索查询，并指导 LLM 聚焦本章节主题，
 * 以降低多个章节检索到相同切片、内容重复的概率。</p>
 */
public record ReportSectionPlan(

        String title,

        String purpose
) {
}
