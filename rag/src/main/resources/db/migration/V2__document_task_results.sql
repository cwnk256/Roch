CREATE TABLE document_results (
    task_id BIGINT PRIMARY KEY REFERENCES document_tasks(id) ON DELETE CASCADE,
    schema_version VARCHAR(20) NOT NULL,
    path TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
