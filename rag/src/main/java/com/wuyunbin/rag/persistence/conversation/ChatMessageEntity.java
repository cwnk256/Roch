package com.wuyunbin.rag.persistence.conversation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor
public class ChatMessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, length = 64)
    private String conversationId;

    @Column(name = "sequence_number", nullable = false)
    private int sequence;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static ChatMessageEntity create(String conversationId, int sequence, String role, String content) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.conversationId = conversationId;
        entity.sequence = sequence;
        entity.role = role;
        entity.content = content;
        entity.createdAt = LocalDateTime.now();
        return entity;
    }
}
