package com.wuyunbin.rag.persistence.conversation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_conversations")
@Getter
@Setter
@NoArgsConstructor
public class ConversationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, unique = true, length = 64)
    private String conversationId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static ConversationEntity create(String conversationId, LocalDateTime now) {
        ConversationEntity entity = new ConversationEntity();
        entity.conversationId = conversationId;
        entity.title = "新会话";
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }
}
