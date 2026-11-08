-- Phase 4 Step 2：切片内容落库 PostgreSQL，为关键词检索（FTS）提供数据基础。
-- 切片同时存在 Milvus（向量）与本标（关键词），两者通过 chunk_id / doc_md5 对齐。
-- tokens 列为 Jieba 分词后空格拼接的结果，使用 simple 配置建 GIN 索引（pg_jieba 不可用时的替代方案）。

CREATE TABLE document_chunks (
    chunk_id        VARCHAR(64) PRIMARY KEY,
    doc_md5         VARCHAR(64) NOT NULL,
    file_name       VARCHAR(255),
    content         TEXT NOT NULL,
    tokens          TEXT NOT NULL,
    chunk_index     INTEGER NOT NULL DEFAULT 0,
    pipeline_version VARCHAR(32),
    block_type      VARCHAR(20),
    section_path    TEXT,
    page_start      INTEGER,
    page_end        INTEGER,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 文档级过滤：关键词检索必须支持 documentIds 范围约束
CREATE INDEX idx_document_chunks_doc_md5 ON document_chunks(doc_md5);

-- 全文检索 GIN 索引：simple 配置配合 Java 侧 Jieba 分词结果
CREATE INDEX idx_document_chunks_tokens_fts
    ON document_chunks USING GIN (to_tsvector('simple', tokens));
