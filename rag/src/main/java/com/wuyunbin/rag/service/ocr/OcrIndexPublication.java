package com.wuyunbin.rag.service.ocr;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

@Component
public class OcrIndexPublication {
    static final String GENERATION = "ocr_generation";
    private static final int MAX_MILVUS_TOP_K = 16_384;
    private final NamedParameterJdbcTemplate sql;

    public OcrIndexPublication(NamedParameterJdbcTemplate sql) {
        this.sql = sql;
    }

    public void stageOriginal(String documentId, boolean previouslyPublished) {
        sql.update("""
                INSERT INTO ocr_index_publication (document_id, source_published)
                VALUES (:id, :published) ON CONFLICT (document_id) DO NOTHING
                """, Map.of("id", documentId, "published", previouslyPublished));
    }

    public boolean originalPublished(String documentId) {
        return sql.query("""
                SELECT source_published FROM ocr_index_publication WHERE document_id = :id
                """, Map.of("id", documentId), (row, n) -> row.getBoolean(1)).stream().findFirst().orElse(true);
    }

    public void publish(String documentId, UUID generation) {
        if (generation == null) {
            sql.update("""
                    UPDATE ocr_index_publication SET active_generation = NULL, source_published = TRUE
                    WHERE document_id = :id
                    """, Map.of("id", documentId));
            return;
        }
        sql.update("""
                INSERT INTO ocr_index_publication (document_id, active_generation, source_published)
                VALUES (:id, :generation, TRUE)
                ON CONFLICT (document_id) DO UPDATE
                SET active_generation = EXCLUDED.active_generation, source_published = TRUE
                """, Map.of("id", documentId, "generation", generation));
    }

    public void unpublishGeneration(String documentId) {
        sql.update("UPDATE ocr_index_publication SET active_generation = NULL WHERE document_id = :id",
                Map.of("id", documentId));
    }

    public List<Document> search(VectorStore store, SearchRequest request) {
        int requested = request.getTopK();
        int limit = requested;
        for (;;) {
            var candidates = store.similaritySearch(limit == requested ? request
                    : SearchRequest.from(request).topK(limit).build());
            var published = visible(candidates, Document::getMetadata);
            if (published.size() >= requested) return List.copyOf(published.subList(0, requested));
            if (candidates.size() < limit || limit >= MAX_MILVUS_TOP_K) return published;
            // Staging can rank ahead of committed rows. Refill the original ranked window.
            limit = Math.min(MAX_MILVUS_TOP_K, limit * 2);
        }
    }

    public <T> List<T> visible(List<T> candidates, Function<T, Map<String, Object>> metadata) {
        var documentIds = candidates.stream().map(metadata)
                .map(row -> Objects.toString(row.get("doc_md5"), ""))
                .filter(id -> !id.isBlank()).distinct().toList();
        if (documentIds.isEmpty()) return candidates;
        var generations = new HashMap<String, String>();
        // Read a single committed generation per source, even while Milvus still contains both attempts.
        for (int start = 0; start < documentIds.size(); start += 1000) {
            var ids = documentIds.subList(start, Math.min(start + 1000, documentIds.size()));
            sql.query("""
                    SELECT document_id, active_generation FROM ocr_index_publication
                    WHERE document_id IN (:ids) AND active_generation IS NOT NULL
                    """, Map.of("ids", ids), row -> {
                generations.put(row.getString(1), row.getString(2));
            });
        }
        return candidates.stream().filter(candidate -> visible(metadata.apply(candidate), generations)).toList();
    }

    private static boolean visible(Map<String, Object> row, Map<String, String> generations) {
        String expected = generations.get(Objects.toString(row.get("doc_md5"), ""));
        String generation = Objects.toString(row.get(GENERATION), null);
        if (expected != null || generation != null) return expected != null && expected.equals(generation);
        return !OcrArtifactStore.isOcrVersion(Objects.toString(row.get("chunk_pipeline_version"), null));
    }

    public UUID markVectors(List<Document> chunks) {
        UUID generation = UUID.randomUUID();
        chunks.forEach(chunk -> chunk.getMetadata().put(GENERATION, generation.toString()));
        return generation;
    }
}
