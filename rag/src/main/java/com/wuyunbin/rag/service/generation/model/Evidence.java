package com.wuyunbin.rag.service.generation.model;

import com.wuyunbin.rag.dto.RetrievedChunk;

/**
 * 证据（引用）：标记一段生成正文来源于哪个文档的哪个切片。
 *
 * <p>证据必须来源于真实检索命中（{@link RetrievedChunk}），不允许由 LLM 编造。
 * 引用链可追溯：document → chunk → section → page（若有）。</p>
 *
 * <p>当前 Phase 1 切片未写入 PDF 页码，{@code pageStart/pageEnd} 可能为 null，
 * 此处保持 null，绝不补造页码。</p>
 */
public record Evidence(

        String documentId,

        String fileName,

        String chunkId,

        String section,

        Integer pageStart,

        Integer pageEnd,

        double score
) {

    /**
     * 由检索命中切片构造证据，原样保留页码（可能为 null，不伪造）。
     */
    public static Evidence from(RetrievedChunk chunk) {
        return new Evidence(
                chunk.documentId(),
                chunk.fileName(),
                chunk.chunkId(),
                chunk.section(),
                chunk.pageStart(),
                chunk.pageEnd(),
                chunk.score()
        );
    }
}
