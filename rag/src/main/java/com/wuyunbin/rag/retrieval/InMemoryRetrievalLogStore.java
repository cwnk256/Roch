package com.wuyunbin.rag.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Stream;

/**
 * 检索日志内存存储：有界双端队列，最新记录在队首，超出容量丢弃最旧。
 * 仅保留最近 buffer-size 条，重启即失。
 */
@Component
@RequiredArgsConstructor
public class InMemoryRetrievalLogStore implements RetrievalLogStore {

    private final Deque<RetrievalLogRecord> buffer = new ConcurrentLinkedDeque<>();
    private final RetrievalLogProperties properties;

    @Override
    public void save(RetrievalLogRecord record) {
        buffer.addFirst(record);
        int max = properties.getBufferSize();
        while (buffer.size() > max) {
            buffer.pollLast();
        }
    }

    @Override
    public List<RetrievalLogRecord> findByConversationId(String conversationId, int limit) {
        Stream<RetrievalLogRecord> stream = buffer.stream();
        if (conversationId != null && !conversationId.isBlank()) {
            stream = stream.filter(r -> conversationId.equals(r.conversationId()));
        }
        return stream.limit(Math.max(1, limit)).toList();
    }

    @Override
    public Optional<RetrievalLogRecord> findByLogId(String logId) {
        return buffer.stream()
                .filter(r -> r.logId().equals(logId))
                .findFirst();
    }
}
