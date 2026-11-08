CREATE TABLE document_tasks (
    id BIGSERIAL PRIMARY KEY,
    task_id VARCHAR(80) NOT NULL UNIQUE,
    type VARCHAR(20) NOT NULL,
    instruction TEXT NOT NULL,
    title VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    progress INTEGER NOT NULL DEFAULT 0,
    current_step VARCHAR(80),
    error_code VARCHAR(80),
    error_message TEXT,
    result_path TEXT,
    result_file_name VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);
CREATE INDEX idx_document_tasks_status ON document_tasks(status);
CREATE INDEX idx_document_tasks_created_at ON document_tasks(created_at DESC);

CREATE TABLE document_task_documents (
    task_id BIGINT NOT NULL REFERENCES document_tasks(id) ON DELETE CASCADE,
    document_id VARCHAR(64) NOT NULL,
    ordinal INTEGER NOT NULL,
    PRIMARY KEY (task_id, document_id)
);
CREATE INDEX idx_document_task_documents_document_id ON document_task_documents(document_id);

CREATE TABLE document_artifacts (
    id BIGSERIAL PRIMARY KEY,
    artifact_id VARCHAR(80) NOT NULL UNIQUE,
    task_id BIGINT NOT NULL REFERENCES document_tasks(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    path TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_document_artifacts_task_id ON document_artifacts(task_id);
