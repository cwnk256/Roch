package com.wuyunbin.rag.persistence.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationJpaRepository extends JpaRepository<ConversationEntity, Long> {
    Optional<ConversationEntity> findByConversationId(String conversationId);

    List<ConversationEntity> findAllByOrderByUpdatedAtDesc();
}
