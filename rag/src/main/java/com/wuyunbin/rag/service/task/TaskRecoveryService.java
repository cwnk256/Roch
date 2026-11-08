package com.wuyunbin.rag.service.task;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskRecoveryService implements ApplicationRunner {
    private final JpaDocumentTaskStore store;

    @Override
    public void run(ApplicationArguments args) {
        store.markInterruptedRunningTasksFailed();
    }
}
