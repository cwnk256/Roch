package com.wuyunbin.rag.service.storage;

import java.io.IOException;
import java.util.Optional;

public interface DocumentStorage {
    void save(String documentId, String fileName, byte[] content) throws IOException;

    Optional<StoredDocument> load(String documentId) throws IOException;

    record StoredDocument(String documentId, String fileName, byte[] content) {
    }
}
