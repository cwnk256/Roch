package com.wuyunbin.rag.service;

import com.wuyunbin.rag.persistence.conversation.ChatMessageEntity;
import com.wuyunbin.rag.persistence.conversation.ChatMessageJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationMemoryServiceTest {

    @Mock
    private ChatMessageJpaRepository repository;

    @InjectMocks
    private ConversationMemoryService service;

    @Test
    void historyReadsPersistedMessagesInTheirStoredOrder() {
        when(repository.findByConversationIdOrderBySequenceAsc("conversation-1")).thenReturn(List.of(
                ChatMessageEntity.create("conversation-1", 0, "USER", "first question"),
                ChatMessageEntity.create("conversation-1", 1, "ASSISTANT", "first answer")
        ));

        assertThat(service.history("conversation-1"))
                .extracting(message -> message.role() + ":" + message.content())
                .containsExactly("USER:first question", "ASSISTANT:first answer");
    }

    @Test
    void saveAllReplacesConversationHistoryWithWindowMessages() {
        service.saveAll("conversation-1", List.of(new UserMessage("question"), new AssistantMessage("answer")));

        ArgumentCaptor<List<ChatMessageEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).deleteByConversationId("conversation-1");
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .extracting(ChatMessageEntity::getContent)
                .containsExactly("question", "answer");
    }

    @Test
    void appendPersistsEachMessageWithoutReplacingExistingHistory() {
        when(repository.findByConversationIdOrderBySequenceAsc("conversation-1")).thenReturn(List.of(
                ChatMessageEntity.create("conversation-1", 0, "USER", "first question")
        ));

        service.append("conversation-1", new AssistantMessage("first answer"));

        org.mockito.ArgumentCaptor<ChatMessageEntity> captor = org.mockito.ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSequence()).isEqualTo(1);
        assertThat(captor.getValue().getRole()).isEqualTo("ASSISTANT");
        assertThat(captor.getValue().getContent()).isEqualTo("first answer");
    }
}
