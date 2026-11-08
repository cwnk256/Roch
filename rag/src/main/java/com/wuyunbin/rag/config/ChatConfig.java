package com.wuyunbin.rag.config;

import com.wuyunbin.rag.cleaning.ChunkingProperties;
import com.wuyunbin.rag.splitter.SemanticTextSplitter;
import com.wuyunbin.rag.tool.AmapTools;
import com.wuyunbin.rag.tool.WebPageTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 配置：ChatClient、语义切片器、会话记忆。
 */
@Configuration
public class ChatConfig {
    public ChatConfig() {
        System.out.println("ChatConfig");
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, AmapTools amapTools) {
        return builder.build();

    }

    /**
     * 会话记忆：滑动窗口 + 进程内存储。
     * 覆盖 Spring AI 自动装配的 ChatMemory（其 @ConditionalOnMissingBean 会退让）。
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository,
                                 @Value("${rag.chat.memory.max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(maxMessages)
                .build();
    }

    /**
     * 多轮专用 ChatClient：默认挂载记忆 Advisor。
     * 不可污染既有 chatClient（单轮请求不带 conversationId 会抛异常）。
     */
    @Bean
    public ChatClient multiTurnChatClient(ChatClient.Builder builder, ChatMemory chatMemory,
                                          AmapTools amapTools, WebPageTools webPageTools) {
        return builder.clone()
                .defaultTools(amapTools, webPageTools)
                .build();
    }

    /**
     * 语义切片器：参数由 ChunkingProperties 外置配置（默认 0.7 / [50, 1000] / 批 32）。
     */
    @Bean
    public SemanticTextSplitter semanticTextSplitter(EmbeddingModel embeddingModel,
                                                     ChunkingProperties chunkingProperties) {
        return new SemanticTextSplitter(embeddingModel, chunkingProperties);
    }
}
