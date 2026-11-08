package com.wuyunbin.rag.service;

import com.wuyunbin.rag.dto.ConversationView;
import com.wuyunbin.rag.persistence.conversation.ConversationEntity;
import com.wuyunbin.rag.persistence.conversation.ConversationJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationCatalogServiceTest {

    @Mock
    private ConversationJpaRepository repository;

    @InjectMocks
    private ConversationCatalogService service;

    @Test
    void createPersistsNewConversationWithDefaultTitle() {
        when(repository.save(any(ConversationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ConversationView result = service.create();

        ArgumentCaptor<ConversationEntity> captor = ArgumentCaptor.forClass(ConversationEntity.class);
        verify(repository).save(captor.capture());
        ConversationEntity saved = captor.getValue();
        assertThat(result.conversationId()).isEqualTo(saved.getConversationId());
        assertThat(saved.getTitle()).isEqualTo("新会话");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
    }

    @Test
    void listReturnsConversationsInRepositoryOrder() {
        ConversationEntity older = ConversationEntity.create("older", LocalDateTime.of(2026, 1, 1, 10, 0));
        ConversationEntity newer = ConversationEntity.create("newer", LocalDateTime.of(2026, 1, 2, 10, 0));
        when(repository.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(newer, older));

        List<ConversationView> result = service.list();

        assertThat(result).extracting(ConversationView::conversationId)
                .containsExactly("newer", "older");
    }

    @Test
    void recordFirstUserMessageUpdatesTitleAndTimestamp() {
        ConversationEntity conversation = ConversationEntity.create(
                "conversation-1", LocalDateTime.of(2026, 1, 1, 10, 0));
        when(repository.findByConversationId("conversation-1")).thenReturn(java.util.Optional.of(conversation));
        when(repository.save(any(ConversationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.recordMessage("conversation-1", "如何配置 Milvus？");

        assertThat(conversation.getTitle()).isEqualTo("如何配置 Milvus？");
        assertThat(conversation.getUpdatedAt()).isAfter(conversation.getCreatedAt());
        verify(repository).save(conversation);
    }
}
