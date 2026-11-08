package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.DocumentTask;
import com.wuyunbin.rag.dto.PageResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 {@link ConcurrentHashMap} 的内存任务存储。
 *
 * <p>Phase 2 第一版任务信息只存内存，结果 PDF 落盘；服务重启后任务记录丢失。
 * 这是计划明确允许的范围，不为此引入数据库。</p>
 */
@Component
@ConditionalOnProperty(name = "rag.document-task.persistence", havingValue = "memory")
public class InMemoryDocumentTaskStore implements DocumentTaskStore {

    private final ConcurrentHashMap<String, DocumentTask> tasks = new ConcurrentHashMap<>();

    @Override
    public void save(DocumentTask task) {
        if (task == null || task.getTaskId() == null) {
            throw new IllegalArgumentException("任务及任务 ID 不能为空");
        }
        tasks.put(task.getTaskId(), task);
    }

    @Override
    public Optional<DocumentTask> find(String taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public void update(DocumentTask task) {
        if (task == null || task.getTaskId() == null) {
            throw new IllegalArgumentException("任务及任务 ID 不能为空");
        }
        tasks.put(task.getTaskId(), task);
    }

    @Override
    public PageResult<DocumentTask> list(int page, int size) {
        List<DocumentTask> sorted = tasks.values().stream()
                .sorted(Comparator.comparing(DocumentTask::getCreatedAt).reversed()).toList();
        return PageResult.of(sorted, page, size);
    }
}
