package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.DocumentTask;
import com.wuyunbin.rag.dto.DocumentTaskStatus;
import com.wuyunbin.rag.dto.PageResult;
import com.wuyunbin.rag.persistence.task.DocumentTaskEntity;
import com.wuyunbin.rag.persistence.task.DocumentTaskJpaRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.document-task.persistence", havingValue = "jpa", matchIfMissing = true)
public class JpaDocumentTaskStore implements DocumentTaskStore {
    private final DocumentTaskJpaRepository repository;

    @Override
    @Transactional
    public void save(DocumentTask task) {
        repository.save(toEntity(task, new DocumentTaskEntity()));
    }

    @Override
    @Transactional
    public Optional<DocumentTask> find(String taskId) {
        return repository.findByTaskId(taskId).map(this::toDto);
    }

    @Override
    @Transactional
    public void update(DocumentTask task) {
        DocumentTaskEntity entity = repository.findByTaskId(task.getTaskId())
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + task.getTaskId()));
        repository.save(toEntity(task, entity));
    }

    @Override
    @Transactional
    public PageResult<DocumentTask> list(int page, int size) {
        var result = repository.findAllByOrderByCreatedAtDesc(org.springframework.data.domain.PageRequest.of(page, size));
        return new PageResult<>(result.map(this::toDto).toList(), page, size, result.getTotalElements());
    }

    @Transactional
    public void markInterruptedRunningTasksFailed() {
        repository.findByStatus(DocumentTaskStatus.RUNNING).forEach(entity -> {
            entity.setStatus(DocumentTaskStatus.FAILED);
            entity.setCurrentStep("FAILED");
            entity.setErrorCode("TASK_INTERRUPTED");
            entity.setErrorMessage("服务重启导致任务中断，请重试。");
            entity.setFinishedAt(java.time.LocalDateTime.now());
            repository.save(entity);
        });
    }

    private DocumentTaskEntity toEntity(DocumentTask task, DocumentTaskEntity entity) {
        entity.setTaskId(task.getTaskId());
        entity.setType(task.getType());
        entity.setInstruction(task.getInstruction());
        entity.setTitle(task.getTitle());
        entity.setStatus(task.getStatus());
        entity.setProgress(task.getProgress());
        entity.setCurrentStep(task.getCurrentStep());
        entity.setErrorCode(task.getErrorCode());
        entity.setErrorMessage(task.getErrorMessage());
        entity.setResultPath(task.getResultPath());
        entity.setResultFileName(task.getResultFileName());
        entity.setCreatedAt(task.getCreatedAt());
        entity.setStartedAt(task.getStartedAt());
        entity.setFinishedAt(task.getFinishedAt());
        entity.setDocumentIds(task.getDocumentIds() == null ? new ArrayList<>() : new ArrayList<>(task.getDocumentIds()));
        return entity;
    }

    private DocumentTask toDto(DocumentTaskEntity entity) {
        DocumentTask task = new DocumentTask();
        task.setTaskId(entity.getTaskId());
        task.setType(entity.getType());
        task.setInstruction(entity.getInstruction());
        task.setTitle(entity.getTitle());
        task.setStatus(entity.getStatus());
        task.setProgress(entity.getProgress());
        task.setCurrentStep(entity.getCurrentStep());
        task.setErrorCode(entity.getErrorCode());
        task.setErrorMessage(entity.getErrorMessage());
        task.setResultPath(entity.getResultPath());
        task.setResultFileName(entity.getResultFileName());
        task.setCreatedAt(entity.getCreatedAt());
        task.setStartedAt(entity.getStartedAt());
        task.setFinishedAt(entity.getFinishedAt());
        task.setDocumentIds(new ArrayList<>(entity.getDocumentIds()));
        return task;
    }
}
