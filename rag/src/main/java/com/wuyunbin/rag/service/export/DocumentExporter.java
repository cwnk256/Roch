package com.wuyunbin.rag.service.export;

import com.wuyunbin.rag.service.generation.model.GeneratedDocument;

/**
 * 文档导出器：把结构化 {@link GeneratedDocument} 渲染为文件。
 *
 * <p>Phase 2 第一版只实现 {@link PdfExporter}；MarkdownExporter / WordExporter
 * 后续实现本接口即可，任务流程无需改动。</p>
 */
public interface DocumentExporter {

    /**
     * 导出文档。
     *
     * @param document 结构化文档
     * @param fileStem 落盘文件名主干（使用任务 ID，天然安全、唯一），最终文件为 {@code <fileStem>.pdf}
     * @return 导出结果（含用户可读文件名、落盘路径与 MIME 类型）
     */
    ExportedFile export(GeneratedDocument document, String fileStem);
}
