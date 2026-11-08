package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * 文档切片视图：从 Milvus 切片元数据映射，用于 GET /api/documents/{id}/chunks。
 *
 * <p>原始 metadata 包含：chunk_id, doc_md5, file_name, chunk_index, chunk_total,
 * chunk_pipeline_version, chunk_strategy, section_id, section_path, section_level,
 * block_type, code_lang, file_size, created_at。</p>
 *
 * <p>pageStart/pageEnd 当前未持久化，返回 null（不伪造）。</p>
 */
@Schema(description = "文档切片视图")
public record ChunkView(

        @Schema(description = "切片 ID（SHA-256）")
        String chunkId,

        @Schema(description = "所属文档 ID")
        String documentId,

        @Schema(description = "所属文件名")
        String fileName,

        @Schema(description = "切片正文（仅 chunks 接口返回完整正文，列表/详情不返回）")
        String content,

        @Schema(description = "页码起始（当前未持久化，返回 null）")
        Integer pageStart,

        @Schema(description = "页码结束（当前未持久化，返回 null）")
        Integer pageEnd,

        @Schema(description = "章节路径")
        String section,

        @Schema(description = "切片序号")
        Integer chunkIndex,

        @Schema(description = "切片总数")
        Integer chunkTotal,

        @Schema(description = "块类型（prose/table/code）")
        String blockType
) {

    /**
     * 从 Milvus 切片元数据 Map 构造 ChunkView。
     * 正文从 content 字段读取（若元数据未携带则返回 null）。
     */
    public static ChunkView from(Map<String, Object> row) {
        return new ChunkView(
                str(row.get("chunk_id")),
                str(row.get("doc_md5")),
                str(row.get("file_name")),
                str(row.get("content")),
                null,
                null,
                str(row.get("section_path")),
                intOrNull(row.get("chunk_index")),
                intOrNull(row.get("chunk_total")),
                str(row.get("block_type"))
        );
    }

    private static String str(Object o) {
        return o != null ? String.valueOf(o) : null;
    }

    private static Integer intOrNull(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try {
                return Integer.parseInt(o.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
