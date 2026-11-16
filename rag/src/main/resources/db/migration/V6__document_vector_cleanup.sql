CREATE TABLE document_vector_cleanup (
    batch_id UUID PRIMARY KEY,
    database_name TEXT NOT NULL,
    collection_name TEXT NOT NULL,
    doc_md5 TEXT NOT NULL,
    vector_ids TEXT NOT NULL
);
