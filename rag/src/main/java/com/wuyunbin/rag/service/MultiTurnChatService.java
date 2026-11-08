package com.wuyunbin.rag.service;

import com.wuyunbin.rag.dto.MultiTurnChatRequest;
import com.wuyunbin.rag.dto.MultiTurnChatResponse;
import com.wuyunbin.rag.dto.StreamChunk;
import com.wuyunbin.rag.retrieval.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 多轮问答编排服务：组织 prompt、调用带记忆的 ChatClient、组装响应。
 * 支持 RAG 检索增强：ragEnabled=true 时先从 Milvus 召回相关切片，拼入 System Prompt。
 * 每次检索（含未启用/异常降级）都写一条结构化日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiTurnChatService {

    private final ChatClient multiTurnChatClient;
    private final ConversationMemoryService conversationMemoryService;
    private final ConversationCatalogService conversationCatalogService;
    private final RagRetriever ragRetriever;
    private final RetrievalLogWriter retrievalLogWriter;
    private final RetrievalLogProperties retrievalLogProperties;

    @Value("${rag.chat.rag.enabled:false}")
    private boolean ragEnabledDefault;

    @Value("${rag.chat.rag.top-k:4}")
    private int defaultTopK;

    @Value("${rag.chat.rag.similarity-threshold:0.5}")
    private double defaultThreshold;

    private static int clampTopK(int k) {
        return Math.max(1, Math.min(20, k));
    }

    private static double clampThreshold(double t) {
        return Math.max(0.0, Math.min(1.0, t));
    }

    /**
     * 多轮问答：按 conversationId 维护上下文，不传则自动新建会话。
     * 埋点覆盖四条路径：SKIPPED（未启用）/ HIT（有命中）/ EMPTY（无命中）/ ERROR（异常降级）。
     */
    public MultiTurnChatResponse chat(MultiTurnChatRequest request) {
        validate(request);
        conversationMemoryService.assertValidIdIfPresent(request.conversationId());
        String conversationId = conversationMemoryService.resolveId(request.conversationId());
        conversationCatalogService.ensure(conversationId);
        conversationCatalogService.recordMessage(conversationId, request.message());
        conversationMemoryService.append(conversationId, new UserMessage(request.message()));

        boolean ragEnabled = request.ragEnabled() != null ? request.ragEnabled() : ragEnabledDefault;
        int topK = request.topK() != null ? clampTopK(request.topK()) : defaultTopK;
        double threshold = request.threshold() != null ? clampThreshold(request.threshold()) : defaultThreshold;

        RetrievalLogRecord logRecord;
        List<RetrievalHit> hits = List.of();
        List<Document> docs = List.of();
        boolean ragUsed = false;

        if (!ragEnabled) {
            // 路径 1：未启用检索
            logRecord = RetrievalLogRecord.skipped(conversationId, null, request.message());
        } else {
            long start = System.nanoTime();
            try {
                docs = ragRetriever.retrieve(request.message(), topK, threshold);
            } catch (RuntimeException e) {
                // 路径 4：检索异常，降级为普通问答（不拖垮主链路）
                long latencyMs = (System.nanoTime() - start) / 1_000_000;
                logRecord = RetrievalLogRecord.error(conversationId, null, request.message(),
                        topK, threshold, latencyMs, e);
                log.warn("检索失败，降级为普通问答 conversationId={}", conversationId, e);
                String degradedReply = callModel(conversationId, request.message(), null);
                retrievalLogWriter.write(logRecord);
                return finish(conversationId, degradedReply, false, List.of());
            }
            long latencyMs = (System.nanoTime() - start) / 1_000_000;

            // Document → RetrievalHit（日志与响应 Reference 的单一数据源）
            hits = toHits(docs);
            ragUsed = !hits.isEmpty();
            // 路径 2/3：HIT 或 EMPTY
            logRecord = RetrievalLogRecord.of(conversationId, null, request.message(),
                    topK, threshold, latencyMs, hits, ragUsed);

            // 终端打印召回结果
            printRetrieval(conversationId, request.message(), docs, hits, latencyMs);
        }

        // 拼 prompt 用完整正文（直接取 Document.getText()），RetrievalHit 不承担正文职责
        String systemPrompt = ragUsed ? buildContextPrompt(docs) : null;
        String reply = callModel(conversationId, request.message(), systemPrompt);

        retrievalLogWriter.write(logRecord);
        return finish(conversationId, reply, ragUsed, hits);
    }

    /**
     * 流式多轮问答：先发送 conversationId 事件，再逐块流式输出模型回复。
     * 检索逻辑与 chat() 一致（含四路径埋点），但模型调用走 stream() 而非 call()。
     * MessageChatMemoryAdvisor 在流聚合完成后自动把助手消息写回记忆。
     */
    public Flux<StreamChunk> streamChat(MultiTurnChatRequest request) {
        validate(request);
        conversationMemoryService.assertValidIdIfPresent(request.conversationId());
        String conversationId = conversationMemoryService.resolveId(request.conversationId());
        conversationCatalogService.ensure(conversationId);
        conversationCatalogService.recordMessage(conversationId, request.message());
        conversationMemoryService.append(conversationId, new UserMessage(request.message()));

        boolean ragEnabled = request.ragEnabled() != null ? request.ragEnabled() : ragEnabledDefault;
        int topK = request.topK() != null ? clampTopK(request.topK()) : defaultTopK;
        double threshold = request.threshold() != null ? clampThreshold(request.threshold()) : defaultThreshold;

        // 检索阶段（与 chat() 逻辑一致）
        RetrievalLogRecord logRecord;
        List<RetrievalHit> hits = List.of();
        List<Document> docs = List.of();
        boolean ragUsed = false;

        if (!ragEnabled) {
            logRecord = RetrievalLogRecord.skipped(conversationId, null, request.message());
        } else {
            long start = System.nanoTime();
            try {
                docs = ragRetriever.retrieve(request.message(), topK, threshold);
            } catch (RuntimeException e) {
                long latencyMs = (System.nanoTime() - start) / 1_000_000;
                logRecord = RetrievalLogRecord.error(conversationId, null, request.message(),
                        topK, threshold, latencyMs, e);
                log.warn("检索失败，降级为普通流式问答 conversationId={}", conversationId, e);
                retrievalLogWriter.write(logRecord);
                return persistedStream(conversationId, request.message(), null)
                        .map(text -> new StreamChunk("content", text));
            }
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            hits = toHits(docs);
            ragUsed = !hits.isEmpty();
            logRecord = RetrievalLogRecord.of(conversationId, null, request.message(),
                    topK, threshold, latencyMs, hits, ragUsed);
            printRetrieval(conversationId, request.message(), docs, hits, latencyMs);
        }

        String systemPrompt = ragUsed ? buildContextPrompt(docs) : null;
        final List<RetrievalHit> finalHits = hits;
        final boolean finalRagUsed = ragUsed;
        final RetrievalLogRecord finalLogRecord = logRecord;

        // 先发 conversationId + rag 元数据事件，再流式输出内容
        Flux<StreamChunk> header = Flux.just(
                new StreamChunk("conversation", conversationId),
                new StreamChunk("rag", String.valueOf(finalRagUsed))
        );

        Flux<StreamChunk> content = persistedStream(conversationId, request.message(), systemPrompt)
                .map(text -> new StreamChunk("content", text))
                .doOnComplete(() -> {
                    retrievalLogWriter.write(finalLogRecord);
                    int round = conversationMemoryService.history(conversationId).size() / 2;
                    log.info("流式问答完成 conversationId={} round={} ragUsed={}", conversationId, round, finalRagUsed);
                });

        return header.concatWith(content);
    }

    /**
     * 调用模型的流式接口，返回逐块文本。
     */
    private Flux<String> streamModel(String conversationId, String userMessage, String systemPrompt) {
        var promptBuilder = multiTurnChatClient.prompt()
                .messages(conversationMemoryService.findByConversationId(conversationId));
        if (systemPrompt != null) {
            promptBuilder.system(systemPrompt);
        }
        return promptBuilder.stream().content();
    }

    private Flux<String> persistedStream(String conversationId, String userMessage, String systemPrompt) {
        StringBuilder reply = new StringBuilder();
        return streamModel(conversationId, userMessage, systemPrompt)
                .doOnNext(reply::append)
                .doOnComplete(() -> conversationMemoryService.append(conversationId, new AssistantMessage(reply.toString())));
    }

    /**
     * 终端打印召回结果，便于开发调试时直观查看命中情况。
     */
    private void printRetrieval(String conversationId, String query, List<Document> docs,
                                List<RetrievalHit> hits, long latencyMs) {
        System.out.println();
        System.out.println("================ RAG 召回文档 ================");
        System.out.println("conversationId: " + conversationId);
        System.out.println("query         : " + query);
        System.out.println("召回数量      : " + docs.size());
        List<Document> sorted = new ArrayList<>(docs);
        sorted.sort(Comparator.<Document, Double>comparing(
                d -> d.getScore() == null ? 0.0 : d.getScore()).reversed());
        for (int i = 0; i < sorted.size(); i++) {
            Document doc = sorted.get(i);
            String id = doc.getId();
            Map<String, Object> md = doc.getMetadata();
            // 相似度取 Document.getScore()（Spring AI 由 Milvus 搜索结果回填），
            // 不放在 metadata 中；保留 4 位小数便于阅读
            Double docScore = doc.getScore();
            String scoreText = docScore == null ? "null" : String.format("%.4f", docScore);
            Object chunkIndex = md.getOrDefault("chunk_index", "null");
            Object source = md.getOrDefault("file_name", "null");
            System.out.println("--- doc[" + (i + 1) + "] id=" + id
                    + ", score=" + scoreText
                    + ", chunk_index=" + chunkIndex
                    + ", source=" + source);
            String text = doc.getText();
            String preview = text.length() > 120 ? text.substring(0, 120) + "..." : text;
            System.out.println("[正文] " + preview);
        }
        System.out.println("============ END (共 " + docs.size() + " 篇, 耗时 " + latencyMs + "ms) ============");
        System.out.println();
    }

    /**
     * Document 列表 → RetrievalHit 列表（日志 hits[] 与响应 Reference 的唯一映射入口）。
     */
    private List<RetrievalHit> toHits(List<Document> docs) {
        List<RetrievalHit> hits = new ArrayList<>(docs.size());
        int previewChars = retrievalLogProperties.getPreviewChars();
        for (int i = 0; i < docs.size(); i++) {
            hits.add(RetrievalHit.from(i + 1, docs.get(i), previewChars));
        }
        return hits;
    }

    /**
     * 构造 RAG 上下文 System Prompt（使用命中切片完整正文）。
     */
    private String buildContextPrompt(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是知识库问答助手。仅依据下列资料回答，资料不足时明确说明资料中未提及。\n\n");
        for (int i = 0; i < docs.size(); i++) {
            sb.append("【资料 ").append(i + 1).append("】");
            sb.append(docs.get(i).getText());
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 调用模型，可选传入 System Prompt。
     */
    private String callModel(String conversationId, String userMessage, String systemPrompt) {
        var promptBuilder = multiTurnChatClient.prompt()
                .messages(conversationMemoryService.findByConversationId(conversationId));
        if (systemPrompt != null) {
            promptBuilder.system(systemPrompt);
        }
        return promptBuilder.call().content();
    }

    /**
     * 组装响应：Reference 从 RetrievalHit 转换（保证与日志 hits[] 字段一致）。
     */
    private MultiTurnChatResponse finish(String conversationId, String reply,
                                         boolean ragUsed, List<RetrievalHit> hits) {
        conversationMemoryService.append(conversationId, new AssistantMessage(reply));
        int round = conversationMemoryService.history(conversationId).size() / 2;
        List<MultiTurnChatResponse.Reference> references = hits.stream()
                .map(h -> new MultiTurnChatResponse.Reference(h.fileName(),
                        h.chunkIndex() != null ? h.chunkIndex() : 0, h.score()))
                .toList();
        log.info("多轮问答完成 conversationId={} round={} ragUsed={}", conversationId, round, ragUsed);
        return new MultiTurnChatResponse(conversationId, reply, round, ragUsed, references);
    }

    private void validate(MultiTurnChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        if (request.message().length() > 4000) {
            throw new IllegalArgumentException("消息内容不能超过 4000 字符");
        }
    }
}
