package com.wuyunbin.rag.service.storage;

import java.io.IOException;
import java.util.Optional;

public interface DocumentStorage {
    void save(String documentId, String fileName, byte[] content) throws IOException;

    Optional<StoredDocument> load(String documentId) throws IOException;

    default boolean isAvailable(String documentId) throws IOException {
        return load(documentId).isPresent();
    }

    record StoredDocument(String documentId, String fileName, byte[] content) {
    }
}
