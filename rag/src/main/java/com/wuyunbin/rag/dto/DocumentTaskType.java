package com.wuyunbin.rag.dto;

/**
 * 文档任务类型。
 *
 * <p>Phase 2 第一版只支持：</p>
 * <ul>
 *   <li>{@link #SUMMARY}：资料总结，偏摘要归纳</li>
 *   <li>{@link #REPORT}：正式报告，偏章节化文档</li>
 * </ul>
 */
public enum DocumentTaskType {

    /**
     * 资料总结：偏摘要、归纳。
     */
    SUMMARY,

    /**
     * 正式报告：偏章节化、正式文档。
     */
    REPORT
}
