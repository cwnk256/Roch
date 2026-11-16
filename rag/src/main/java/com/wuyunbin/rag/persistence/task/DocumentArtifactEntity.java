package com.wuyunbin.rag.persistence.task;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_artifacts")
@Getter
@Setter
@NoArgsConstructor
public class DocumentArtifactEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "artifact_id", nullable = false, unique = true, length = 80)
    private String artifactId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private DocumentTaskEntity task;
    @Column(name = "file_name", nullable = false)
    private String fileName;
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;
    @Column(name = "file_size", nullable = false)
    private long fileSize;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String path;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
