package com.wuyunbin.rag.persistence.task;

import com.wuyunbin.rag.dto.DocumentTaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface DocumentTaskJpaRepository extends JpaRepository<DocumentTaskEntity, Long> {
    Optional<DocumentTaskEntity> findByTaskId(String taskId);

    Page<DocumentTaskEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Collection<DocumentTaskEntity> findByStatus(DocumentTaskStatus status);
}
