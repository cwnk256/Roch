package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.dto.ChatMessageView;
import com.wuyunbin.rag.dto.ConversationView;
import com.wuyunbin.rag.dto.MultiTurnChatRequest;
import com.wuyunbin.rag.service.ConversationCatalogService;
import com.wuyunbin.rag.service.ConversationMemoryService;
import com.wuyunbin.rag.service.MultiTurnChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 多轮问答接口。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "多轮问答接口", description = "基于会话记忆的多轮对话接口")
public class MultiTurnChatController {

    private final MultiTurnChatService multiTurnChatService;
    private final ConversationMemoryService conversationMemoryService;
    private final ConversationCatalogService conversationCatalogService;

    @PostMapping("/multi-turn")
    @Operation(summary = "多轮问答", description = "按 conversationId 维护上下文；不传则自动新建会话")
    public ResponseEntity<?> chat(@RequestBody MultiTurnChatRequest request) {
        try {
            return ResponseEntity.ok(multiTurnChatService.chat(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/multi-turn/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式多轮问答", description = "SSE 流式输出；先发 conversation/rag 事件，再逐块输出模型回复")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamChat(@RequestBody MultiTurnChatRequest request) {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L);
        try {
            multiTurnChatService.streamChat(request)
                    .doOnNext(chunk -> {
                        try {
                            emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                    .name(chunk.event())
                                    .data(chunk.data()));
                        } catch (java.io.IOException e) {
                            emitter.completeWithError(e);
                        }
                    })
                    .doOnError(emitter::completeWithError)
                    .doOnComplete(emitter::complete)
                    .subscribe();
        } catch (IllegalArgumentException e) {
            try {
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                        .name("error").data(e.getMessage()));
                emitter.complete();
            } catch (java.io.IOException ignored) {
                emitter.completeWithError(e);
            }
        }
        return emitter;
    }

    @PostMapping("/conversations")
    @Operation(summary = "新建会话", description = "显式创建一个新会话，返回会话 ID")
    public ConversationView create() {
        return conversationCatalogService.create();
    }

    @GetMapping("/conversations")
    @Operation(summary = "查询会话列表", description = "按最后更新时间倒序返回会话目录")
    public List<ConversationView> conversations() {
        return conversationCatalogService.list();
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @Operation(summary = "查询会话历史", description = "返回指定会话的全部历史消息；会话不存在返回空列表")
    public ResponseEntity<?> messages(@PathVariable String conversationId) {
        try {
            conversationMemoryService.assertValidId(conversationId);
            List<ChatMessageView> messages = conversationMemoryService.history(conversationId);
            return ResponseEntity.ok(Map.of("conversationId", conversationId, "messages", messages));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/conversations/{conversationId}")
    @Operation(summary = "清空会话", description = "清空指定会话的全部历史消息；幂等")
    public ResponseEntity<?> clear(@PathVariable String conversationId) {
        try {
            conversationMemoryService.assertValidId(conversationId);
            conversationMemoryService.clear(conversationId);
            return ResponseEntity.ok(Map.of("conversationId", conversationId, "cleared", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/conversations/{conversationId}/delete")
    @Operation(summary = "删除会话", description = "删除指定会话及其全部历史消息；幂等")
    public ResponseEntity<?> delete(@PathVariable String conversationId) {
        try {
            conversationMemoryService.assertValidId(conversationId);
            conversationMemoryService.clear(conversationId);
            conversationCatalogService.delete(conversationId);
            return ResponseEntity.ok(Map.of("conversationId", conversationId, "deleted", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
