CREATE TABLE ocr_index_publication (
    document_id VARCHAR(32) PRIMARY KEY,
    active_generation UUID,
    source_published BOOLEAN NOT NULL
);
