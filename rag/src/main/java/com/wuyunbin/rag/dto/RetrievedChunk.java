package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.ai.document.Document;

import java.util.Map;

/**
 * 检索命中的切片：从 Milvus 检索结果映射而来，供 Document Search API 返回。
 *
 * <p>不直接暴露 Milvus SDK 原始对象。</p>
 */
@Schema(description = "检索命中的文档切片")
public record RetrievedChunk(

        @Schema(description = "切片 ID（SHA-256，跨版本稳定）")
        String chunkId,

        @Schema(description = "所属文档 ID（doc_md5）")
        String documentId,

        @Schema(description = "所属文件名")
        String fileName,

        @Schema(description = "切片正文")
        String content,

        @Schema(description = "章节路径（heading-aware 切片携带，例如 \"设备维护 > 日常检查\"）")
        String section,

        @Schema(description = "页码起始（PDF 切片携带；其他类型为 null，不伪造）")
        Integer pageStart,

        @Schema(description = "页码结束（PDF 切片携带；其他类型为 null，不伪造）")
        Integer pageEnd,

        @Schema(description = "切片在文档内的序号")
        Integer chunkIndex,

        @Schema(description = "相似度得分（0~1，越大越相似）")
        double score
) {

    /**
     * 从 Spring AI Document 构造 RetrievedChunk。
     * pageStart/pageEnd 当前切片元数据未携带，返回 null（不伪造）。
     */
    public static RetrievedChunk from(Document doc) {
        Map<String, Object> md = doc.getMetadata();
        Double rawScore = doc.getScore();
        Object sectionPath = md.get("section_path");
        String section = (sectionPath != null && !sectionPath.toString().isBlank())
                ? sectionPath.toString()
                : null;
        return new RetrievedChunk(
                str(md.get("chunk_id")),
                str(md.get("doc_md5")),
                str(md.get("file_name")),
                doc.getText() == null ? "" : doc.getText(),
                section,
                null,
                null,
                intOrNull(md.get("chunk_index")),
                rawScore != null ? rawScore : 0.0
        );
    }

    private static String str(Object o) {
        return o != null ? String.valueOf(o) : null;
    }

    private static Integer intOrNull(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(o.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
