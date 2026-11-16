package com.wuyunbin.rag.service.task;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.document-task.persistence", havingValue = "jpa", matchIfMissing = true)
public class TaskRecoveryService implements SmartInitializingSingleton {
    private final JpaDocumentTaskStore store;

    @Override
    public void afterSingletonsInstantiated() {
        // 单实例部署：在 Web 服务接收请求前收尾上次运行遗留的任务。
        store.failInterruptedTasks();
    }
}
