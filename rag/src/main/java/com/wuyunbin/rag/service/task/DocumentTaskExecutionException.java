package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.DocumentTask;
import lombok.Getter;

/**
 * 文档任务执行期异常（检索 / 生成 / PDF 导出失败）。
 *
 * <p>抛出前 {@link DocumentTaskService} 已把任务置为 FAILED 并记录 errorMessage；
 * 控制器据此返回 500，与参数错误（400）、文档不存在（404）区分。</p>
 */
@Getter
public class DocumentTaskExecutionException extends RuntimeException {

    private final transient DocumentTask task;

    public DocumentTaskExecutionException(DocumentTask task, Throwable cause) {
        super("文档任务执行失败: " + task.getTaskId(), cause);
        this.task = task;
    }
}
