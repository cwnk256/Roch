package com.wuyunbin.rag.service;

import com.wuyunbin.rag.dto.ChatMessageView;
import com.wuyunbin.rag.persistence.conversation.ChatMessageEntity;
import com.wuyunbin.rag.persistence.conversation.ChatMessageJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 会话管理服务：基于 ChatMemory 的会话创建、校验、历史读取、清空。
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class ConversationMemoryService implements org.springframework.ai.chat.memory.ChatMemoryRepository {

    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final ChatMessageJpaRepository repository;

    /**
     * 创建新会话 ID。
     */
    public String create() {
        return UUID.randomUUID().toString();
    }

    /**
     * 校验会话 ID 格式，非法时抛异常。
     * 适用于必须显式指定 ID 的场景（messages/clear 接口）。
     * null、空白字符串、格式不匹配均视为非法。
     */
    public void assertValidId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()
                || !ID_PATTERN.matcher(conversationId).matches()) {
            throw new IllegalArgumentException("conversationId 格式非法");
        }
    }

    /**
     * 宽松校验：仅当 ID 非空时检查格式。
     * 适用于 chat 接口（null/blank 会由 resolveId 自动新建会话）。
     */
    public void assertValidIdIfPresent(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()
                && !ID_PATTERN.matcher(conversationId).matches()) {
            throw new IllegalArgumentException("conversationId 格式非法");
        }
    }

    /**
     * 解析会话 ID：为空则创建新 ID，否则原样返回。
     */
    public String resolveId(String conversationId) {
        return (conversationId == null || conversationId.isBlank()) ? create() : conversationId;
    }

    /**
     * 读取会话历史消息。
     */
    public List<ChatMessageView> history(String conversationId) {
        return findByConversationId(conversationId).stream()
                .map(m -> new ChatMessageView(roleName(m.getMessageType()), m.getText()))
                .toList();
    }

    /**
     * 清空指定会话。
     */
    @Transactional
    public void clear(String conversationId) {
        deleteByConversationId(conversationId);
        log.info("会话已清空: {}", conversationId);
    }

    @Override
    public List<String> findConversationIds() {
        return repository.findDistinctConversationIds();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> findByConversationId(String conversationId) {
        return repository.findByConversationIdOrderBySequenceAsc(conversationId).stream()
                .map(this::toMessage)
                .toList();
    }

    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        repository.deleteByConversationId(conversationId);
        repository.saveAll(java.util.stream.IntStream.range(0, messages.size())
                .mapToObj(index -> toEntity(conversationId, index, messages.get(index)))
                .toList());
    }

    @Transactional
    public void append(String conversationId, Message message) {
        int sequence = repository.findByConversationIdOrderBySequenceAsc(conversationId).size();
        repository.save(toEntity(conversationId, sequence, message));
    }

    @Override
    @Transactional
    public void deleteByConversationId(String conversationId) {
        repository.deleteByConversationId(conversationId);
    }

    private ChatMessageEntity toEntity(String conversationId, int sequence, Message message) {
        return ChatMessageEntity.create(conversationId, sequence, roleName(message.getMessageType()), message.getText());
    }

    private Message toMessage(ChatMessageEntity entity) {
        return switch (entity.getRole()) {
            case "USER" -> new UserMessage(entity.getContent());
            case "ASSISTANT" -> new AssistantMessage(entity.getContent());
            case "SYSTEM" -> new SystemMessage(entity.getContent());
            default -> new UserMessage(entity.getContent());
        };
    }

    private String roleName(MessageType type) {
        return switch (type) {
            case USER -> "USER";
            case ASSISTANT -> "ASSISTANT";
            case SYSTEM -> "SYSTEM";
            case TOOL -> "TOOL";
        };
    }
}
