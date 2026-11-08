package com.wuyunbin.rag.persistence.task;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_results")
@Getter
@Setter
@NoArgsConstructor
public class DocumentResultEntity implements Persistable<Long> {
    @Id
    @Column(name = "task_id")
    private Long taskId;
    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "task_id", nullable = false)
    private DocumentTaskEntity task;
    @Column(name = "schema_version", nullable = false, length = 20)
    private String schemaVersion;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String path;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Transient
    private boolean newEntity = true;

    @Override
    public Long getId() {
        return taskId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        newEntity = false;
    }
}
