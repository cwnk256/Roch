package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.DocumentTask;
import com.wuyunbin.rag.dto.PageResult;

import java.util.Optional;

/**
 * 文档任务存储抽象。
 *
 * <p>Phase 2 第一版使用内存实现（{@link InMemoryDocumentTaskStore}）；
 * 抽象接口便于未来替换为 JPA / Redis / 数据库而不影响 TaskService。</p>
 */
public interface DocumentTaskStore {

    /**
     * 保存新任务。
     */
    void save(DocumentTask task);

    /**
     * 按任务 ID 查询。
     */
    Optional<DocumentTask> find(String taskId);

    /**
     * 更新已有任务（状态、结果路径、错误信息等）。
     */
    void update(DocumentTask task);

    default PageResult<DocumentTask> list(int page, int size) {
        throw new UnsupportedOperationException("当前任务存储不支持分页查询");
    }
}
