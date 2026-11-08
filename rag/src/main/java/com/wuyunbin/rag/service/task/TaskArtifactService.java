package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.DocumentArtifactResponse;
import com.wuyunbin.rag.persistence.task.DocumentArtifactEntity;
import com.wuyunbin.rag.persistence.task.DocumentArtifactJpaRepository;
import com.wuyunbin.rag.persistence.task.DocumentTaskEntity;
import com.wuyunbin.rag.persistence.task.DocumentTaskJpaRepository;
import com.wuyunbin.rag.service.export.ExportedFile;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskArtifactService {
    private final DocumentTaskJpaRepository tasks;
    private final DocumentArtifactJpaRepository artifacts;

    @Transactional
    public DocumentArtifactResponse register(String taskId, ExportedFile file) {
        DocumentTaskEntity task = tasks.findByTaskId(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        DocumentArtifactEntity entity = new DocumentArtifactEntity();
        entity.setArtifactId("artifact-" + UUID.randomUUID().toString().replace("-", ""));
        entity.setTask(task);
        entity.setFileName(file.fileName());
        entity.setContentType(file.contentType());
        entity.setPath(file.path().toString());
        entity.setCreatedAt(LocalDateTime.now());
        try {
            entity.setFileSize(Files.size(file.path()));
        } catch (IOException e) {
            throw new IllegalStateException("读取生成文件失败", e);
        }
        artifacts.save(entity);
        return toResponse(entity);
    }

    @Transactional
    public List<DocumentArtifactResponse> list(String taskId) {
        return artifacts.findByTaskTaskIdOrderByCreatedAtAsc(taskId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public Path findPath(String taskId, String artifactId) {
        DocumentArtifactEntity a = artifacts.findByArtifactId(artifactId).filter(x -> x.getTask().getTaskId().equals(taskId)).orElseThrow(() -> new IllegalArgumentException("资产不存在"));
        return Path.of(a.getPath());
    }

    private DocumentArtifactResponse toResponse(DocumentArtifactEntity a) {
        return new DocumentArtifactResponse(a.getArtifactId(), a.getFileName(), a.getContentType(), a.getFileSize(), a.getCreatedAt(), "/api/document-tasks/" + a.getTask().getTaskId() + "/artifacts/" + a.getArtifactId());
    }
}
