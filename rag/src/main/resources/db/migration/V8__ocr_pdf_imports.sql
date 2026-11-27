CREATE TABLE ocr_pdf_imports (
    job_id UUID PRIMARY KEY,
    document_id VARCHAR(32) NOT NULL UNIQUE,
    file_name TEXT NOT NULL,
    source_pdf BYTEA,
    status VARCHAR(12) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'INDEXING', 'SUCCEEDED', 'FAILED')),
    current_page INTEGER NOT NULL DEFAULT 0,
    total_pages INTEGER NOT NULL DEFAULT 0,
    failed_page INTEGER,
    message TEXT NOT NULL,
    result_chunk_count INTEGER,
    result_duplicated BOOLEAN,
    queued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ocr_pdf_imports_queue ON ocr_pdf_imports (queued_at, job_id) WHERE status = 'QUEUED';
CREATE INDEX idx_ocr_pdf_imports_running ON ocr_pdf_imports (status) WHERE status IN ('RUNNING', 'INDEXING');
