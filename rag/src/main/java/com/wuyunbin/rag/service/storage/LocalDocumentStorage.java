package com.wuyunbin.rag.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
        replace(dir, "source.bin", content);
        replace(dir, "filename", (fileName == null ? "document" : fileName)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void replace(Path dir, String name, byte[] content) throws IOException {
        var target = dir.resolve(name);
        if (Files.isRegularFile(target) && Files.size(target) == content.length
                && java.util.Arrays.equals(Files.readAllBytes(target), content)) return;
        Path temporary = Files.createTempFile(dir, ".source-", ".tmp");
        try {
            Files.write(temporary, content);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public boolean isAvailable(String documentId) {
        Path source = root.resolve(documentId).resolve("source.bin").normalize();
        return source.startsWith(root) && Files.isRegularFile(source) && Files.isReadable(source);
    }

    public Optional<StoredDocument> load(String documentId) throws IOException {
        Path dir = root.resolve(documentId).normalize();
        if (!dir.startsWith(root) || !Files.isRegularFile(dir.resolve("source.bin"))) return Optional.empty();
        String name = Files.isRegularFile(dir.resolve("filename")) ? Files.readString(dir.resolve("filename")) : documentId;
        return Optional.of(new StoredDocument(documentId, name, Files.readAllBytes(dir.resolve("source.bin"))));
    }
}
