package com.wuyunbin.rag.service.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyunbin.rag.persistence.task.DocumentResultEntity;
import com.wuyunbin.rag.persistence.task.DocumentResultJpaRepository;
import com.wuyunbin.rag.persistence.task.DocumentTaskEntity;
import com.wuyunbin.rag.persistence.task.DocumentTaskJpaRepository;
import com.wuyunbin.rag.service.generation.model.GeneratedDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@RequiredArgsConstructor
public class GeneratedDocumentStorage {
    private final ObjectMapper objectMapper;
    private final DocumentTaskProperties properties;
    private final DocumentTaskJpaRepository tasks;
    private final DocumentResultJpaRepository resultRepository;

    public Path save(String taskId, GeneratedDocument document) {
        try {
            Path dir = Paths.get(properties.getOutputDir(), taskId);
            Files.createDirectories(dir);
            Path output = dir.resolve("result.json");
            objectMapper.writeValue(output.toFile(), document);
            DocumentTaskEntity task = tasks.findByTaskId(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
            DocumentResultEntity result = resultRepository.findByTaskTaskId(taskId)
                    .orElseGet(DocumentResultEntity::new);
            result.setTask(task);
            result.setTaskId(task.getId());
            result.setSchemaVersion("v1");
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
                    return objectMapper.readValue(candidate.toFile(), GeneratedDocument.class);
                } catch (IOException e) {
                    // 文件存在但内容损坏/结构不兼容：直接抛出，不再尝试其他候选
                    throw new IllegalStateException("读取结构化结果失败（文件损坏或结构不兼容）: " + candidate, e);
                }
            }
        }
        throw new IllegalStateException("结构化结果文件不存在: " + taskId
                + "（尝试过: " + candidates + "）");
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
