package com.wuyunbin.rag.service;

import com.wuyunbin.rag.dto.ConversationView;
import com.wuyunbin.rag.persistence.conversation.ConversationEntity;
import com.wuyunbin.rag.persistence.conversation.ConversationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationCatalogService {

    private static final String DEFAULT_TITLE = "新会话";
    private final ConversationJpaRepository repository;

    @Transactional
    public ConversationView create() {
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        ConversationEntity saved = repository.save(ConversationEntity.create(id, now));
        return toView(saved);
    }

    @Transactional
    public void ensure(String conversationId) {
        if (repository.findByConversationId(conversationId).isEmpty()) {
            repository.save(ConversationEntity.create(conversationId, LocalDateTime.now()));
        }
    }

    @Transactional
    public void delete(String conversationId) {
        repository.findByConversationId(conversationId)
                .ifPresent(repository::delete);
    }

    @Transactional(readOnly = true)
    public List<ConversationView> list() {
        return repository.findAllByOrderByUpdatedAtDesc().stream().map(this::toView).toList();
    }

    @Transactional
    public void recordMessage(String conversationId, String message) {
        ConversationEntity conversation = repository.findByConversationId(conversationId)
                .orElseGet(() -> ConversationEntity.create(conversationId, LocalDateTime.now()));
        if (DEFAULT_TITLE.equals(conversation.getTitle())) {
            conversation.setTitle(toTitle(message));
        }
        conversation.setUpdatedAt(LocalDateTime.now());
        repository.save(conversation);
    }

    private String toTitle(String message) {
        String normalized = message == null ? "" : message.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 60) {
            return normalized.isEmpty() ? DEFAULT_TITLE : normalized;
        }
        return normalized.substring(0, 60) + "…";
    }

    private ConversationView toView(ConversationEntity entity) {
        return new ConversationView(entity.getConversationId(), entity.getTitle(),
                entity.getCreatedAt().toString(), entity.getUpdatedAt().toString());
    }
}
