package com.wuyunbin.rag.persistence.chunk;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 切片落库实体：与 Milvus 中的向量切片通过 chunk_id / doc_md5 对齐。
 *
 * <p>tokens 列为 Jieba 分词后空格拼接的文本，供 PostgreSQL FTS（simple 配置）建索引检索。</p>
 */
@Entity
@Table(name = "document_chunks")
@Getter
@Setter
@NoArgsConstructor
public class DocumentChunkEntity {

    @Id
    @Column(name = "chunk_id", length = 64)
    private String chunkId;

    @Column(name = "doc_md5", nullable = false, length = 64)
    private String docMd5;

    @Column(name = "file_name")
    private String fileName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Jieba 分词后空格拼接的 token 串，供 to_tsvector('simple', tokens) 检索。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String tokens;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "pipeline_version", length = 32)
    private String pipelineVersion;

    @Column(name = "block_type", length = 20)
    private String blockType;

    @Column(name = "section_path", columnDefinition = "TEXT")
    private String sectionPath;

    @Column(name = "page_start")
    private Integer pageStart;

    @Column(name = "page_end")
    private Integer pageEnd;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
