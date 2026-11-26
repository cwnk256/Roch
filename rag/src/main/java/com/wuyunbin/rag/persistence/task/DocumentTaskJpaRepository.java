package com.wuyunbin.rag.persistence.task;

import com.wuyunbin.rag.dto.DocumentTaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Collection;

public interface DocumentTaskJpaRepository extends JpaRepository<DocumentTaskEntity, Long> {
    Optional<DocumentTaskEntity> findByTaskId(String taskId);

    boolean existsByStatusIn(Collection<DocumentTaskStatus> statuses);

    Page<DocumentTaskEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Modifying
    @Query("""
            update DocumentTaskEntity task set task.status = FAILED, task.currentStep = 'FAILED',
                task.errorCode = 'TASK_INTERRUPTED', task.errorMessage = '服务重启导致任务中断，请重试。',
                task.finishedAt = local datetime
            where task.status in (PENDING, RUNNING)
            """)
    int failInterruptedTasks();

    @Modifying
    @Query("""
            update DocumentTaskEntity task set task.status = RUNNING, task.progress = 10,
                task.currentStep = 'RUNNING', task.startedAt = local datetime
            where task.taskId = :taskId and task.status = PENDING
            """)
    int claimRunning(@Param("taskId") String taskId);
}
