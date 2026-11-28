package com.wuyunbin.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** 记录物理向量 ID，使数据库提交、回滚或进程退出后的清理均可重试。 */
@Slf4j
@Component
@EnableScheduling
public class VectorCleanupJournal {
    private final JdbcTemplate jdbc;
    private final VectorStore vectorStore;
    private final TransactionTemplate independent;
    private final String database;
    private final String collection;

    public VectorCleanupJournal(JdbcTemplate jdbc, VectorStore vectorStore, PlatformTransactionManager transactions,
                                @Value("${spring.ai.vectorstore.milvus.databaseName:default}") String database,
                                @Value("${spring.ai.vectorstore.milvus.collectionName:rag_docs}") String collection) {
        this.jdbc = jdbc;
        this.vectorStore = vectorStore;
        this.database = database;
        this.collection = collection;
        this.independent = new TransactionTemplate(transactions);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 调用方持有文档事务锁。先独立登记新 ID，再在切片事务中将清理目标改成旧 ID。 */
    UUID prepare(String documentId, List<String> newIds, List<String> previousIds) {
        UUID batchId = UUID.randomUUID();
        independent.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO document_vector_cleanup (batch_id, database_name, collection_name, doc_md5, vector_ids)
                VALUES (?, ?, ?, ?, ?)
                """, batchId, database, collection, documentId, String.join("\n", newIds)));
        if (previousIds.isEmpty()) {
            jdbc.update("DELETE FROM document_vector_cleanup WHERE batch_id = ?", batchId);
        } else {
            jdbc.update("UPDATE document_vector_cleanup SET vector_ids = ? WHERE batch_id = ?",
                    String.join("\n", previousIds), batchId);
        }
        return batchId;
    }

    void clean(UUID batchId) {
        independent.executeWithoutResult(status -> {
            // 正在导入的文档由写事务持锁；其他线程只能在其提交或回滚后清理。
            List<String> batches = jdbc.queryForList("""
                    SELECT vector_ids FROM document_vector_cleanup
                    WHERE batch_id = ? AND pg_try_advisory_xact_lock(hashtextextended(doc_md5, 0))
                    FOR UPDATE SKIP LOCKED
                    """, String.class, batchId);
            for (String batch : batches) {
                List<String> ids = Arrays.asList(batch.split("\n"));
                for (int offset = 0; offset < ids.size(); offset += 1000) {
                    vectorStore.delete(ids.subList(offset, Math.min(offset + 1000, ids.size())));
                }
                jdbc.update("DELETE FROM document_vector_cleanup WHERE batch_id = ?", batchId);
            }
        });
    }

    @Scheduled(initialDelay = 10000, fixedDelay = 60000)
    public void retryPendingCleanup() {
        var batches = jdbc.queryForList("""
                SELECT batch_id FROM document_vector_cleanup
                WHERE database_name = ? AND collection_name = ?
                """, UUID.class, database, collection);
        for (UUID batchId : batches) {
            try {
                clean(batchId);
            } catch (RuntimeException ex) {
                log.warn("向量清理失败，稍后重试 batchId={}", batchId, ex);
            }
        }
    }
}
