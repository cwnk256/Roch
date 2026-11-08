package com.wuyunbin.rag.service.generation.model;

import java.util.List;

/**
 * 最终结构化文档：标题 + 摘要 + 有序章节 + 去重后的参考资料。
 *
 * <p>这是生成层与导出层之间的唯一数据契约：LLM 只产出本对象，
 * 再由 {@code DocumentExporter}（PdfExporter / 未来的 MarkdownExporter）渲染成文件，
 * 业务不直接耦合 PDF。</p>
 */
public record GeneratedDocument(

        String title,

        String summary,

        List<GeneratedSection> sections,

        List<Evidence> references
) {
}
