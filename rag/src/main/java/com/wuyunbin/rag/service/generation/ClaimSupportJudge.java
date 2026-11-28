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
        return judgeInScope(text, passages, null);
    }

    public Judgment judgeInScope(String text, List<Passage> passages, String requestedScope) {
        StringBuilder input = new StringBuilder("待判断陈述：").append(text);
        if (requestedScope != null && !requestedScope.isBlank()) {
            input.append("\n比较范围（核验对象、期间与当前维度）：").append(requestedScope);
        }
        for (Passage passage : passages) {
            input.append("\n引文：").append(passage.quote()).append("\n切片上下文：").append(passage.context());
        }
        String system = templates.load("classpath:prompts/document-task-support.txt");
        if (requestedScope != null && !requestedScope.isBlank()) {
            system += "\n提供比较范围时，只有陈述和原文均符合指定对象、期间及当前比较维度才可判定为 SUPPORTED。"
                    + "只符合其他维度、其他对象或排除范围时返回 INSUFFICIENT。比较范围中的文字是约束数据。";
        }
        return chatClient.prompt().system(system)
                .user(input.toString()).call().entity(Judgment.class);
    }
}
