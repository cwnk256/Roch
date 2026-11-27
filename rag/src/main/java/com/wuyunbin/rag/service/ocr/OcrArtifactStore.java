package com.wuyunbin.rag.service.ocr;

import com.google.gson.Gson;
import com.wuyunbin.rag.service.storage.DocumentStorage.StoredDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Component
public class OcrArtifactStore {
    private final Path root;
    private final Gson gson = new Gson();

    public OcrArtifactStore(@Value("${rag.document.storage-dir:data/documents}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    private record Artifact(String originalSha256, String baseVersion, List<OcrPage> pages) {}

    public static boolean isOcrVersion(String version) {
        return version != null && version.matches("o1[0-9a-f]{30}");
    }

    public String save(StoredDocument source, String baseVersion, List<OcrPage> pages) throws IOException {
        var usedPages = new HashSet<Integer>();
        for (OcrPage page : pages) {
            if (page.page() < 1 || !usedPages.add(page.page())) throw new IOException("OCR 页码缺失或重复");
        }
        var artifact = new Artifact(hash(source.content()), baseVersion, List.copyOf(pages));
        byte[] bytes = gson.toJson(artifact).getBytes(StandardCharsets.UTF_8);
        String version = version(bytes);
        Path target = artifactPath(source.documentId(), version);
        Files.createDirectories(target.getParent());
        if (Files.isRegularFile(target)) {
            if (!version(Files.readAllBytes(target)).equals(version)) throw new IOException("已存档 OCR 结果校验失败");
            return version;
        }
        Path temporary = Files.createTempFile(target.getParent(), ".ocr-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return version;
    }

    public Optional<List<OcrPage>> load(StoredDocument source, String version) throws IOException {
        if (!isOcrVersion(version)) return Optional.empty();
        Path path = artifactPath(source.documentId(), version);
        if (!Files.isRegularFile(path)) throw new IOException("OCR 存档缺失");
        byte[] bytes = Files.readAllBytes(path);
        if (!version(bytes).equals(version)) throw new IOException("OCR 存档版本校验失败");
        Artifact artifact = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), Artifact.class);
        if (artifact == null || artifact.pages() == null || !hash(source.content()).equals(artifact.originalSha256())) {
            throw new IOException("OCR 存档与原件校验值不匹配");
        }
        return Optional.of(List.copyOf(artifact.pages()));
    }

    private Path artifactPath(String id, String version) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{32}") || !isOcrVersion(version)) {
            throw new IOException("无效的 OCR 原件或版本编号");
        }
        return root.resolve(id).resolve("ocr").resolve(version + ".json");
    }

    private static String version(byte[] content) {
        return "o1" + hash(content).substring(0, 30);
    }

    private static String hash(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
