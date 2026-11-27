package com.wuyunbin.rag.service.ocr;

import com.wuyunbin.rag.dto.DocumentResponse;
import com.wuyunbin.rag.service.storage.DocumentStorage.StoredDocument;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

@Repository
class OcrImportJobStore {
    private static final String JOB_COLUMNS = "job_id, document_id, status, current_page, total_pages, "
            + "failed_page, message, result_chunk_count, result_duplicated";
    private final JdbcTemplate jdbc;

    OcrImportJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    OcrImportJob enqueue(StoredDocument source) {
        var existing = findByDocumentId(source.documentId());
        if (existing.isPresent() && existing.get().status() != OcrImportJob.Status.SUCCEEDED) {
            return existing.get();
        }
        // 与文档入库、删除共用事务锁；已删除的文档允许用同一原件重新导入。
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))",
                Integer.class, source.documentId());
        existing = findByDocumentId(source.documentId());
        if (existing.isEmpty()) {
            return jdbc.queryForObject("""
                    INSERT INTO ocr_pdf_imports (job_id, document_id, file_name, source_pdf, status, message)
                    VALUES (?, ?, ?, ?, 'QUEUED', '等待 OCR 识别')
                    RETURNING %s
                    """.formatted(JOB_COLUMNS), (row, number) -> mapJob(row), UUID.randomUUID(),
                    source.documentId(), source.fileName(), source.content());
        }
        OcrImportJob job = existing.get();
        if (job.status() != OcrImportJob.Status.SUCCEEDED) {
            return job;
        }
        return jdbc.query("""
                UPDATE ocr_pdf_imports
                SET file_name = ?, source_pdf = ?, status = 'QUEUED', message = '等待 OCR 识别',
                    current_page = 0, total_pages = 0, failed_page = NULL, queued_at = CURRENT_TIMESTAMP,
                    result_chunk_count = NULL, result_duplicated = NULL
                WHERE job_id = ? AND (NOT EXISTS (SELECT 1 FROM document_chunks WHERE doc_md5 = ?)
                    OR NOT EXISTS (SELECT 1 FROM ocr_index_publication
                                   WHERE document_id = ocr_pdf_imports.document_id AND active_generation IS NOT NULL))
                RETURNING %s
                """.formatted(JOB_COLUMNS), (row, number) -> mapJob(row), source.fileName(), source.content(),
                job.jobId(), source.documentId()).stream().findFirst().orElse(job);
    }

    Optional<OcrImportJob> find(UUID jobId) {
        return jdbc.query("SELECT " + JOB_COLUMNS + " FROM ocr_pdf_imports WHERE job_id = ?",
                (row, number) -> mapJob(row), jobId).stream().findFirst();
    }

    private Optional<OcrImportJob> findByDocumentId(String documentId) {
        return jdbc.query("SELECT " + JOB_COLUMNS + " FROM ocr_pdf_imports WHERE document_id = ?",
                (row, number) -> mapJob(row), documentId).stream().findFirst();
    }

    Optional<OcrImportJob> retry(UUID jobId) {
        return jdbc.query("""
                UPDATE ocr_pdf_imports
                SET status = 'QUEUED', current_page = 0, total_pages = 0, failed_page = NULL,
                    message = '等待 OCR 重试', queued_at = CURRENT_TIMESTAMP
                WHERE job_id = ? AND status = 'FAILED'
                RETURNING %s
                """.formatted(JOB_COLUMNS), (row, number) -> mapJob(row), jobId)
                .stream().findFirst().or(() -> find(jobId));
    }

    void recoverInterrupted() {
        jdbc.update("""
                UPDATE ocr_pdf_imports
                SET status = 'QUEUED', current_page = 0, total_pages = 0, failed_page = NULL,
                    message = '服务重启后等待 OCR 识别'
                WHERE status IN ('RUNNING', 'INDEXING')
                """);
    }

    Optional<OcrWork> claimNext() {
        return jdbc.query("""
                UPDATE ocr_pdf_imports SET status = 'RUNNING', message = '正在识别 PDF'
                WHERE job_id = (SELECT job_id FROM ocr_pdf_imports WHERE status = 'QUEUED'
                                ORDER BY queued_at, job_id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING job_id, document_id, file_name, source_pdf
                """, (row, number) -> new OcrWork(row.getObject("job_id", UUID.class), new StoredDocument(
                row.getString("document_id"), row.getString("file_name"), row.getBytes("source_pdf"))))
                .stream().findFirst();
    }

    void totalPages(UUID jobId, int totalPages) {
        jdbc.update("UPDATE ocr_pdf_imports SET total_pages = ? WHERE job_id = ? AND status = 'RUNNING'",
                totalPages, jobId);
    }

    void currentPage(UUID jobId, int currentPage) {
        jdbc.update("UPDATE ocr_pdf_imports SET current_page = ? WHERE job_id = ? AND status = 'RUNNING'",
                currentPage, jobId);
    }

    void markIndexing(UUID jobId) {
        jdbc.update("""
                UPDATE ocr_pdf_imports SET status = 'INDEXING', message = '正在建立文档索引'
                WHERE job_id = ? AND status = 'RUNNING'
                """, jobId);
    }

    void complete(UUID jobId, DocumentResponse result) {
        int completed = jdbc.update("""
                UPDATE ocr_pdf_imports
                SET status = 'SUCCEEDED', source_pdf = NULL, current_page = total_pages,
                    message = ?, result_chunk_count = ?, result_duplicated = ?
                WHERE job_id = ? AND status = 'INDEXING'
                """, result.message(), result.chunkCount(), result.duplicated(), jobId);
        if (completed != 1) {
            throw new IllegalStateException("OCR 任务无法完成");
        }
    }

    void fail(UUID jobId, boolean pageFailure, String message) {
        jdbc.update("""
                UPDATE ocr_pdf_imports
                SET failed_page = CASE WHEN ? THEN NULLIF(current_page, 0) END, status = 'FAILED', message = ?
                WHERE job_id = ? AND status IN ('RUNNING', 'INDEXING')
                """, pageFailure, message, jobId);
    }

    void withWorkerLock(Runnable work) {
        // 会话锁在识别期间保持有效，进程退出时 PostgreSQL 会释放锁。
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                boolean acquired;
                try (ResultSet lock = statement.executeQuery("SELECT pg_try_advisory_lock(hashtext('roch'), hashtext('ocr_pdf_imports'))")) {
                    acquired = lock.next() && lock.getBoolean(1);
                }
                if (acquired) {
                    try {
                        work.run();
                    } finally {
                        statement.execute("SELECT pg_advisory_unlock(hashtext('roch'), hashtext('ocr_pdf_imports'))");
                    }
                }
            }
            return null;
        });
    }

    private static OcrImportJob mapJob(ResultSet row) throws SQLException {
        String documentId = row.getString("document_id");
        String message = row.getString("message");
        var status = OcrImportJob.Status.valueOf(row.getString("status"));
        DocumentResponse result = status == OcrImportJob.Status.SUCCEEDED
                ? new DocumentResponse(row.getInt("result_chunk_count"), documentId,
                row.getBoolean("result_duplicated"), message)
                : null;
        return new OcrImportJob(row.getObject("job_id", UUID.class), documentId, status,
                row.getInt("current_page"), row.getInt("total_pages"), row.getObject("failed_page", Integer.class),
                message, result);
    }

    record OcrWork(UUID jobId, StoredDocument source) {
    }
}
