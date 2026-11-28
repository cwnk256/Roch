package com.wuyunbin.rag.persistence.chunk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DocumentChunkJpaRepository extends JpaRepository<DocumentChunkEntity, String> {

    List<DocumentChunkEntity> findByDocMd5(String docMd5);

    @Query("SELECT DISTINCT c.pipelineVersion FROM DocumentChunkEntity c WHERE c.docMd5 = :docMd5")
    List<String> findPipelineVersions(@Param("docMd5") String docMd5);

    List<DocumentChunkEntity> findTop4ByDocMd5AndPageStartAndChunkIndexOrderByChunkIdAsc(
            String docMd5, Integer pageStart, Integer chunkIndex);

    @Query(value = """
            SELECT * FROM document_chunks
            WHERE doc_md5 IN :docIds AND page_start > 0
              AND to_tsvector('simple', tokens) @@ to_tsquery('simple', :query)
            ORDER BY ts_rank(to_tsvector('simple', tokens), to_tsquery('simple', :query)) DESC,
                     doc_md5, page_start, chunk_index, chunk_id
            LIMIT :limit
            """, nativeQuery = true)
    List<DocumentChunkEntity> searchReportAnchors(
            @Param("query") String query, @Param("docIds") Collection<String> docIds, @Param("limit") int limit);

    @Modifying
    @Query("DELETE FROM DocumentChunkEntity c WHERE c.docMd5 = :docMd5")
    void deleteByDocMd5(@Param("docMd5") String docMd5);

    /**
     * 删除指定文档除当前版本外的旧版本切片（按 pipeline_version 区分）。
     */
    /** 同一原文的导入、重新索引及删除按 PostgreSQL 事务串行执行。 */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:docMd5, 0))", nativeQuery = true)
    int lockDocument(@Param("docMd5") String docMd5);

    /**
     * 关键词检索：FTS 匹配 + 可选文档范围过滤，按 ts_rank 降序返回。
     * 使用 simple 配置，tokens 列已由 Java 侧 Jieba 分词。
     * query 参数为 Jieba 分词后用 " | " 连接的 OR 语义 tsquery 串。
     */
    @Query(value = """
            SELECT * FROM document_chunks
            WHERE to_tsvector('simple', tokens) @@ to_tsquery('simple', :query)
              AND (:docFilter = false OR doc_md5 IN :docIds)
            ORDER BY ts_rank(to_tsvector('simple', tokens), to_tsquery('simple', :query)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<DocumentChunkEntity> searchByKeywords(
            @Param("query") String query,
            @Param("docFilter") boolean docFilter,
            @Param("docIds") Collection<String> docIds,
            @Param("limit") int limit);
}
