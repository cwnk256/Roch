package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.service.generation.model.SupportStatus;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class ClaimSupportJudge {
    public record Passage(String quote, String context) {}
    public record Judgment(SupportStatus status, String reason) {}

    private final ChatClient chatClient;
    private final PromptTemplates templates;
    public ClaimSupportJudge(ChatClient chatClient, PromptTemplates templates) {
        this.chatClient = chatClient;
        this.templates = templates;
    }

    public Judgment judge(String text, List<Passage> passages) {
        StringBuilder input = new StringBuilder("待判断陈述：").append(text);
        for (Passage passage : passages) {
            input.append("\n引文：").append(passage.quote()).append("\n切片上下文：").append(passage.context());
        }
        return chatClient.prompt().system(templates.load("classpath:prompts/document-task-support.txt"))
                .user(input.toString()).call().entity(Judgment.class);
    }
}
