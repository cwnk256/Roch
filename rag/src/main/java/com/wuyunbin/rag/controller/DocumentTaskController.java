package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.dto.*;
import com.wuyunbin.rag.service.task.DocumentTaskExecutionException;
import com.wuyunbin.rag.service.task.DocumentTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

/**
 * 文档任务接口：基于用户指定的 1~N 个资料，经检索 + 大纲 + 章节生成，导出 PDF。
 */
@RestController
@RequestMapping("/api/document-tasks")
@RequiredArgsConstructor
@Tag(name = "文档任务接口", description = "基于指定资料的智能文档生成（SUMMARY / REPORT）与 PDF 下载")
public class DocumentTaskController {

    private final DocumentTaskService documentTaskService;

    @PostMapping
    @Operation(summary = "创建并执行文档任务",
            description = "传入 type（SUMMARY/REPORT）、至少 1 个 documentId 与自然语言 instruction；"
                    + "同步执行并在完成后返回任务状态与下载地址")
    public ResponseEntity<?> createTask(@RequestBody DocumentTaskRequest request) {
        try {
            return ResponseEntity.ok(documentTaskService.createAndExecute(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            // 指定文档不存在或未就绪
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (DocumentTaskExecutionException e) {
            // 执行 / 导出失败：任务已置 FAILED，返回 500 与任务状态
            return ResponseEntity.status(500).body(DocumentTaskResponse.from(e.getTask()));
        }
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "查询任务状态", description = "按 taskId 查询任务状态；不存在返回 404")
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        Optional<DocumentTask> found = documentTaskService.find(taskId);
        if (found.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "任务不存在: " + taskId));
        }
        return ResponseEntity.ok(DocumentTaskResponse.from(found.get()));
    }

    @GetMapping
    @Operation(summary = "查询任务历史", description = "按创建时间倒序分页返回任务")
    public ResponseEntity<?> listTasks(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100)
            return ResponseEntity.badRequest().body(Map.of("error", "分页参数非法"));
        PageResult<DocumentTask> result = documentTaskService.list(page, size);
        return ResponseEntity.ok(new PageResult<>(result.content().stream().map(DocumentTaskResponse::from).toList(), result.page(), result.size(), result.total()));
    }

    @GetMapping("/{taskId}/result")
    @Operation(summary = "读取结构化生成结果")
    public ResponseEntity<?> getResult(@PathVariable String taskId) {
        try {
            return ResponseEntity.ok(documentTaskService.readResult(taskId));
        } catch (IllegalStateException e) {
            // 带上具体原因（缺失/损坏与尝试过的路径），前端原样展示
            return ResponseEntity.status(404).body(Map.of("error",
                    e.getMessage() == null ? "结构化结果不存在: " + taskId : e.getMessage()));
        }
    }

    @GetMapping("/{taskId}/artifacts")
    @Operation(summary = "查询任务资产")
    public ResponseEntity<?> listArtifacts(@PathVariable String taskId) {
        if (documentTaskService.find(taskId).isEmpty())
            return ResponseEntity.status(404).body(Map.of("error", "任务不存在: " + taskId));
        return ResponseEntity.ok(documentTaskService.artifacts(taskId));
    }

    @GetMapping("/{taskId}/artifacts/{artifactId}")
    @Operation(summary = "下载指定资产")
    public ResponseEntity<?> downloadArtifact(@PathVariable String taskId, @PathVariable String artifactId) {
        try {
            Path path = documentTaskService.artifactPath(taskId, artifactId);
            if (!Files.isReadable(path)) return ResponseEntity.status(404).body(Map.of("error", "资产文件不存在"));
            DocumentArtifactResponse artifact = documentTaskService.artifacts(taskId).stream().filter(a -> a.artifactId().equals(artifactId)).findFirst().orElseThrow();
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(artifact.fileName(), StandardCharsets.UTF_8).build().toString())
                    .contentType(MediaType.parseMediaType(artifact.contentType())).contentLength(artifact.fileSize()).body(new FileSystemResource(path));
        } catch (RuntimeException e) {
            return ResponseEntity.status(404).body(Map.of("error", "资产不存在"));
        }
    }

    @PostMapping("/{taskId}/retry")
    @Operation(summary = "重试失败任务", description = "创建一个新的任务，不覆盖原任务及其资产")
    public ResponseEntity<?> retry(@PathVariable String taskId) {
        try {
            return ResponseEntity.accepted().body(documentTaskService.retry(taskId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{taskId}/artifacts/pdf")
    @Operation(summary = "再次导出 PDF", description = "从已保存的 result.json 重建 PDF，不调用 LLM")
    public ResponseEntity<?> reExportPdf(@PathVariable String taskId) {
        try {
            return ResponseEntity.ok(documentTaskService.reExportPdf(taskId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{taskId}/file")
    @Operation(summary = "下载任务生成的 PDF",
            description = "任务不存在 404；任务未完成 409；结果文件缺失 404；成功返回 application/pdf")
    public ResponseEntity<?> downloadFile(@PathVariable String taskId) {
        DocumentTask task = documentTaskService.find(taskId).orElse(null);
        if (task == null) {
            return ResponseEntity.status(404).body(Map.of("error", "任务不存在: " + taskId));
        }
        if (task.getStatus() != DocumentTaskStatus.COMPLETED) {
            return ResponseEntity.status(409).body(Map.of(
                    "taskId", task.getTaskId(),
                    "status", task.getStatus().name(),
                    "error", task.getErrorMessage() != null ? task.getErrorMessage() : "任务尚未完成"));
        }

        Path path = task.getResultPath() == null ? null : Paths.get(task.getResultPath());
        if (path == null || !Files.isReadable(path)) {
            return ResponseEntity.status(404).body(Map.of("error", "结果文件不存在: " + taskId));
        }

        try {
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(task.getResultFileName(), StandardCharsets.UTF_8)
                    .build();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .contentType(MediaType.APPLICATION_PDF)
                    .contentLength(Files.size(path))
                    .body(new FileSystemResource(path));
        } catch (java.io.IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", "读取结果文件失败: " + e.getMessage()));
        }
    }
}
