package com.wuyunbin.rag.persistence.task;

import com.wuyunbin.rag.dto.DocumentTaskStatus;
import com.wuyunbin.rag.dto.DocumentTaskType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "document_tasks")
@Getter
@Setter
@NoArgsConstructor
public class DocumentTaskEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_id", nullable = false, unique = true, length = 80)
    private String taskId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentTaskType type;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String instruction;
    private String title;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentTaskStatus status;
    @Column(nullable = false)
    private int progress;
    @Column(name = "current_step", length = 80)
    private String currentStep;
    @Column(name = "error_code", length = 80)
    private String errorCode;
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    @Column(name = "result_path", columnDefinition = "TEXT")
    private String resultPath;
    @Column(name = "result_file_name")
    private String resultFileName;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "started_at")
    private LocalDateTime startedAt;
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "document_task_documents", joinColumns = @JoinColumn(name = "task_id"))
    @Column(name = "document_id", nullable = false)
    @OrderColumn(name = "ordinal")
    private List<String> documentIds = new ArrayList<>();
}
