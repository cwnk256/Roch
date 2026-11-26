package com.wuyunbin.rag.service.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyunbin.rag.persistence.task.DocumentResultEntity;
import com.wuyunbin.rag.persistence.task.DocumentResultJpaRepository;
import com.wuyunbin.rag.persistence.task.DocumentTaskEntity;
import com.wuyunbin.rag.persistence.task.DocumentTaskJpaRepository;
import com.wuyunbin.rag.service.generation.model.GeneratedDocument;
import com.wuyunbin.rag.service.generation.model.CurrentSource;
import com.wuyunbin.rag.service.generation.model.Evidence;
import com.wuyunbin.rag.service.generation.ClaimVerifier;
import com.wuyunbin.rag.service.storage.DocumentStorage;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkEntity;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class GeneratedDocumentStorage {
    private final ObjectMapper objectMapper;
    private final DocumentTaskProperties properties;
    private final DocumentTaskJpaRepository tasks;
    private final DocumentResultJpaRepository resultRepository;
    private final DocumentChunkJpaRepository chunks;
    private final DocumentStorage originals;

    @Autowired
    public GeneratedDocumentStorage(ObjectMapper objectMapper, DocumentTaskProperties properties,
                                    DocumentTaskJpaRepository tasks, DocumentResultJpaRepository resultRepository,
                                    DocumentChunkJpaRepository chunks, DocumentStorage originals) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.tasks = tasks;
        this.resultRepository = resultRepository;
        this.chunks = chunks;
        this.originals = originals;
    }

    public GeneratedDocumentStorage(ObjectMapper objectMapper, DocumentTaskProperties properties,
                                    DocumentTaskJpaRepository tasks, DocumentResultJpaRepository resultRepository) {
        this(objectMapper, properties, tasks, resultRepository, null, null);
    }

    public Path save(String taskId, GeneratedDocument document) {
        return save(taskId, document, Path.of(properties.getOutputDir()));
    }

    public Path save(String taskId, GeneratedDocument document, Path outputRoot) {
        try {
            Path dir = outputRoot.resolve(taskId);
            Files.createDirectories(dir);
            Path output = dir.resolve("result.json");
            DocumentTaskEntity task = tasks.findByTaskId(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
            Path temporary = Files.createTempFile(dir, "result-", ".tmp");
            try {
                objectMapper.writeValue(temporary.toFile(), document.withCurrentSources(Map.of()));
                try {
                    Files.move(temporary, output, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            DocumentResultEntity result = resultRepository.findByTaskTaskId(taskId)
                    .orElseGet(DocumentResultEntity::new);
            result.setTask(task);
            result.setTaskId(task.getId());
            result.setSchemaVersion(document.schemaVersion());
            // 存绝对路径：不同实例（IDE / mvn）工作目录不同，相对路径会导致其他实例读不到
            result.setPath(output.toAbsolutePath().toString());
            result.setCreatedAt(java.time.LocalDateTime.now());
            resultRepository.save(result);
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("保存结构化结果失败", e);
        }
    }

    /**
     * 读取任务的结构化结果。
     *
     * <p>解析顺序：</p>
     * <ol>
     *     <li>数据库 result 表记录的路径（新任务为绝对路径，跨实例稳定）</li>
     *     <li>相对路径时，按当前工作目录、其父目录分别解析，并兼容 rag/ 模块前缀
     *     （历史实例可能以仓库根或 rag 模块为工作目录启动）</li>
     *     <li>按 outputDir 直接拼接（同上多工作目录尝试）</li>
     * </ol>
     * 文件存在但损坏时立即抛出；全部不可读时抛出含尝试路径的异常，便于定位。
     */
    public GeneratedDocument read(String taskId) {
        java.util.List<Path> candidates = candidatePaths(taskId);
        for (Path candidate : candidates) {
            if (Files.isReadable(candidate)) {
                try {
                    return withCurrentSources(objectMapper.readValue(candidate.toFile(), GeneratedDocument.class));
                } catch (IOException e) {
                    // 文件存在但内容损坏/结构不兼容：直接抛出，不再尝试其他候选
                    throw new IllegalStateException("读取结构化结果失败（文件损坏或结构不兼容）: " + candidate, e);
                }
            }
        }
        throw new IllegalStateException("结构化结果文件不存在: " + taskId
                + "（尝试过: " + candidates + "）");
    }

    private GeneratedDocument withCurrentSources(GeneratedDocument document) {
        if (document.evidenceSnapshots().isEmpty() || originals == null || chunks == null) return document;
        Map<String, Boolean> originalAvailability = new HashMap<>();
        Map<String, DocumentChunkEntity> currentChunks = new HashMap<>();
        boolean lookupSucceeded = true;
        try {
            chunks.findAllById(document.evidenceSnapshots().stream().map(Evidence::chunkId).distinct().toList())
                    .forEach(chunk -> currentChunks.put(chunk.getChunkId(), chunk));
        } catch (RuntimeException ex) {
            lookupSucceeded = false;
        }
        Map<String, CurrentSource> sources = new LinkedHashMap<>();
        for (Evidence evidence : document.evidenceSnapshots()) {
            String id = evidence.documentId();
            if (!originalAvailability.containsKey(id)) {
                try {
                    originalAvailability.put(id, originals.isAvailable(id));
                } catch (IOException | RuntimeException ex) {
                    originalAvailability.put(id, null);
                }
            }
            DocumentChunkEntity current = currentChunks.get(evidence.chunkId());
            CurrentSource.ChunkStatus status = CurrentSource.ChunkStatus.UNKNOWN;
            if (lookupSucceeded) {
                if (current == null || !id.equals(current.getDocMd5())) status = CurrentSource.ChunkStatus.MISSING;
                else if (evidence.textHash() != null && current.getContent() != null) {
                    boolean same = evidence.textHash().equals(ClaimVerifier.hashText(current.getContent()))
                            && Objects.equals(evidence.pipelineVersion(), current.getPipelineVersion())
                            && Objects.equals(evidence.section(), current.getSectionPath())
                            && Objects.equals(evidence.pageStart(), current.getPageStart())
                            && Objects.equals(evidence.pageEnd(), current.getPageEnd());
                    status = same ? CurrentSource.ChunkStatus.UNCHANGED : CurrentSource.ChunkStatus.CHANGED;
                }
            }
            sources.put(evidence.evidenceId(), new CurrentSource(originalAvailability.get(id), status));
        }
        return document.withCurrentSources(sources);
    }

    private java.util.List<Path> candidatePaths(String taskId) {
        String outputDir = properties.getOutputDir();
        java.util.List<Path> candidates = new java.util.ArrayList<>();
        // 数据库记录路径优先；相对路径需按多工作目录候选解析（历史实例工作目录不统一）
        resultRepository.findByTaskTaskId(taskId)
                .map(DocumentResultEntity::getPath)
                .ifPresent(p -> {
                    Path path = Paths.get(p);
                    if (path.isAbsolute()) {
                        candidates.add(path);
                        return;
                    }
                    for (Path base : workingDirCandidates()) {
                        candidates.add(base.resolve(p));
                        candidates.add(base.resolve("rag").resolve(p));
                    }
                });
        Path derived = Paths.get(outputDir, taskId, "result.json");
        for (Path base : workingDirCandidates()) {
            candidates.add(base.resolve(derived));
        }
        return candidates.stream().distinct().toList();
    }

    /**
     * 工作目录候选：当前目录 + 父目录（IDE 从仓库根启动、mvn 从模块目录启动的两种情形）。
     */
    private java.util.List<Path> workingDirCandidates() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path parent = cwd.getParent();
        return parent == null
                ? java.util.List.of(cwd)
                : java.util.List.of(cwd, parent);
    }
}
