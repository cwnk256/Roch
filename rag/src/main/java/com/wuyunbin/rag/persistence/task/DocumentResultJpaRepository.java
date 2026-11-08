package com.wuyunbin.rag.persistence.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentResultJpaRepository extends JpaRepository<DocumentResultEntity, Long> {
    Optional<DocumentResultEntity> findByTaskTaskId(String taskId);
}
