package com.wuyunbin.rag.desktop;

import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("desktop")
final class DesktopIndexBinding implements SmartInitializingSingleton {
    private final JdbcTemplate jdbc;
    private final DesktopSettingsStore store;
    private final DocumentChunkJpaRepository chunks;

    DesktopIndexBinding(JdbcTemplate jdbc, DesktopSettingsStore store, DocumentChunkJpaRepository chunks) {
        this.jdbc = jdbc;
        this.store = store;
        this.chunks = chunks;
    }

    boolean locked() {
        return chunks.count() != 0 || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM document_vector_cleanup)", Boolean.class));
    }

    @Override
    public void afterSingletonsInstantiated() {
        String collection = store.activeConnection().collectionName();
        var existing = jdbc.queryForList("SELECT collection_name FROM desktop_index_connection WHERE singleton_id = 1",
                String.class);
        if (locked() && (existing.isEmpty() || !existing.getFirst().equals(collection))) {
            throw new IllegalStateException("现有索引与新的嵌入模型配置不一致。请恢复上一次连接配置后重试。");
        }
        jdbc.update("""
                INSERT INTO desktop_index_connection (singleton_id, collection_name) VALUES (1, ?)
                ON CONFLICT (singleton_id) DO UPDATE SET collection_name = EXCLUDED.collection_name
                """, collection);
    }
}
