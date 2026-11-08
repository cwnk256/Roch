package com.wuyunbin.rag.persistence.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentArtifactJpaRepository extends JpaRepository<DocumentArtifactEntity, Long> {
    Optional<DocumentArtifactEntity> findByArtifactId(String artifactId);

    List<DocumentArtifactEntity> findByTaskTaskIdOrderByCreatedAtAsc(String taskId);
}
