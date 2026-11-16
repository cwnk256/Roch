package com.wuyunbin.rag.persistence.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageJpaRepository extends JpaRepository<ChatMessageEntity, Long> {
    List<ChatMessageEntity> findByConversationIdOrderBySequenceAsc(String conversationId);

    @org.springframework.data.jpa.repository.Query("select distinct message.conversationId from ChatMessageEntity message")
    List<String> findDistinctConversationIds();

    void deleteByConversationId(String conversationId);
}
