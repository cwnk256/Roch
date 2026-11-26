package com.wuyunbin.rag.desktop;

import com.wuyunbin.rag.dto.ReportGenerationSnapshot;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.function.Supplier;

public final class DesktopChatOptions implements BaseAdvisor {
    private static final ThreadLocal<ReportGenerationSnapshot> reportSettings = new ThreadLocal<>();

    private final DesktopSettingsStore store;

    DesktopChatOptions(DesktopSettingsStore store) {
        this.store = store;
    }

    public static <T> T withReportSnapshot(ReportGenerationSnapshot settings, Supplier<T> generate) {
        if (settings == null) return generate.get();
        var previous = reportSettings.get();
        reportSettings.set(settings);
        try {
            return generate.get();
        } finally {
            if (previous == null) reportSettings.remove();
            else reportSettings.set(previous);
        }
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        var settings = reportSettings.get();
        if (settings == null) settings = store.generationSnapshot();
        if (settings.model().isBlank()) {
            throw new IllegalStateException("请先在设置中选择并保存 LM Studio 生成模型。");
        }
        var options = OpenAiChatOptions.builder();
        if (request.prompt().getOptions() != null) {
            options.combineWith(request.prompt().getOptions().mutate());
        }
        options.baseUrl(DesktopSettings.normalizeOrigin(settings.modelOrigin()) + "/v1")
                .model(settings.model())
                .temperature(settings.temperature())
                .topP(settings.topP())
                .maxTokens(settings.maxTokens());
        return request.mutate().prompt(new Prompt(request.prompt().getInstructions(), options.build())).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
