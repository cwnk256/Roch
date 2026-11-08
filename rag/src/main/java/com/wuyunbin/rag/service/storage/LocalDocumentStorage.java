package com.wuyunbin.rag.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Service
public class LocalDocumentStorage implements DocumentStorage {
    private final Path root;

    public LocalDocumentStorage(@Value("${rag.document.storage-dir:data/documents}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public void save(String documentId, String fileName, byte[] content) throws IOException {
        Files.createDirectories(root);
        Path dir = root.resolve(documentId).normalize();
        if (!dir.startsWith(root)) throw new IOException("Invalid document id");
        Files.createDirectories(dir);
        Files.write(dir.resolve("source.bin"), content);
        Files.writeString(dir.resolve("filename"), fileName == null ? "document" : fileName);
    }

    public Optional<StoredDocument> load(String documentId) throws IOException {
        Path dir = root.resolve(documentId).normalize();
        if (!dir.startsWith(root) || !Files.isRegularFile(dir.resolve("source.bin"))) return Optional.empty();
        String name = Files.isRegularFile(dir.resolve("filename")) ? Files.readString(dir.resolve("filename")) : documentId;
        return Optional.of(new StoredDocument(documentId, name, Files.readAllBytes(dir.resolve("source.bin"))));
    }
}
