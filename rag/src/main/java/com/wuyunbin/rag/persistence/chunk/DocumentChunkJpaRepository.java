package com.wuyunbin.rag.persistence.chunk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DocumentChunkJpaRepository extends JpaRepository<DocumentChunkEntity, String> {

    List<DocumentChunkEntity> findByDocMd5(String docMd5);

    @Modifying
    @Query("DELETE FROM DocumentChunkEntity c WHERE c.docMd5 = :docMd5")
    void deleteByDocMd5(@Param("docMd5") String docMd5);

    /**
     * 删除指定文档除当前版本外的旧版本切片（按 pipeline_version 区分）。
     */
    @Modifying
    @Query("DELETE FROM DocumentChunkEntity c WHERE c.docMd5 = :docMd5 AND c.pipelineVersion <> :keepVersion")
    void deleteOldVersions(@Param("docMd5") String docMd5, @Param("keepVersion") String keepVersion);

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
