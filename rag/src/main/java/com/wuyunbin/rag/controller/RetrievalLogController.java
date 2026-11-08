package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.retrieval.RetrievalLogRecord;
import com.wuyunbin.rag.retrieval.RetrievalLogStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 检索日志查询接口。
 * 数据来源为内存缓冲（仅保留最近 buffer-size 条，重启即失，非审计日志）。
 */
@RestController
@RequestMapping("/api/retrieval-logs")
@RequiredArgsConstructor
@Tag(name = "检索日志查询接口", description = "查询被召回文档的检索日志记录（内存缓冲，重启即失）")
public class RetrievalLogController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final RetrievalLogStore retrievalLogStore;

    /**
     * 查询检索日志列表，倒序返回（最新在前）。
     *
     * @param conversationId 可选，按会话 ID 过滤
     * @param limit          返回条数，范围 [1, 200]，默认 50
     */
    @GetMapping
    @Operation(summary = "查询检索日志列表", description = "倒序返回最近的检索记录，支持按 conversationId 过滤；limit 范围 1-200")
    public ResponseEntity<?> list(
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            return ResponseEntity.badRequest().body(Map.of("error", "limit 范围为 1-200"));
        }
        List<RetrievalLogRecord> items = retrievalLogStore.findByConversationId(conversationId, limit);
        return ResponseEntity.ok(Map.of("total", items.size(), "items", items));
    }

    /**
     * 按 logId 查询单条检索日志。
     */
    @GetMapping("/{logId}")
    @Operation(summary = "按 logId 查询单条检索日志", description = "内存缓冲未命中时返回 404")
    public ResponseEntity<?> get(@PathVariable String logId) {
        return retrievalLogStore.findByLogId(logId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
