package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.dto.*;
import com.wuyunbin.rag.service.task.DocumentTaskExecutionException;
import com.wuyunbin.rag.service.task.DocumentTaskService;
import com.wuyunbin.rag.service.task.TaskProgressPublisher;
import com.wuyunbin.rag.service.task.ReportTemplates;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
@Tag(name = "文档任务接口", description = "基于指定资料的智能文档生成（SUMMARY / REPORT / COMPARE）与 PDF 下载")
public class DocumentTaskController {

    private final DocumentTaskService documentTaskService;
    private final TaskProgressPublisher progressPublisher;

    @GetMapping("/templates")
    @Operation(summary = "读取内置文档模板")
    public java.util.List<ReportTemplates.Template> templates() {
        return ReportTemplates.list();
    }

    @PostMapping
    @Operation(summary = "创建并执行文档任务",
            description = "传入 type、documentIds 与自然语言 instruction；COMPARE 至少需要两份不同文档。"
                    + "异步执行，立即返回 PENDING 快照")
    @ApiResponse(responseCode = "200", description = "新任务的初始快照",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DocumentTaskResponse.class)))
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
    @ApiResponse(responseCode = "200", description = "当前任务快照",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DocumentTaskResponse.class)))
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        Optional<DocumentTask> found = documentTaskService.find(taskId);
        if (found.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "任务不存在: " + taskId));
        }
        return ResponseEntity.ok(DocumentTaskResponse.from(found.get()));
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅任务进度", description = "progress 事件返回任务快照；首次连接立即推送，终态后关闭")
    public SseEmitter taskEvents(@PathVariable String taskId) {
        return progressPublisher.open(taskId, () -> documentTaskService.find(taskId)
                .map(DocumentTaskResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在")));
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
    @ApiResponse(responseCode = "202", description = "重试产生的新任务快照",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DocumentTaskResponse.class)))
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
    public ResponseEntity<?> reExportPdf(@PathVariable String taskId, @RequestBody(required = false) ReportExportOptions options) {
        try {
            return ResponseEntity.ok(documentTaskService.reExportPdf(taskId, options));
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
