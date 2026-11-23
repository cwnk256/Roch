package com.wuyunbin.rag.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@Profile("desktop")
public class DesktopConfiguration {
    public static ApplicationContextInitializer<ConfigurableApplicationContext> initializer() {
        return context -> {
            var environment = context.getEnvironment();
            if (!environment.acceptsProfiles(Profiles.of("desktop"))) return;
            try {
                Path path = requiredPath("ROCH_DESKTOP_SETTINGS_FILE");
                var store = new DesktopSettingsStore(path, new ObjectMapper());
                var connection = store.activeConnection();
                context.getBeanFactory().registerSingleton("desktopSettingsStore", store);
                Path root = path.getParent();
                environment.getPropertySources().addFirst(new MapPropertySource("rochDesktop", Map.ofEntries(
                        Map.entry("server.address", "127.0.0.1"),
                        Map.entry("server.port", 0),
                        Map.entry("server.shutdown", "graceful"),
                        Map.entry("spring.ai.openai.base-url", connection.modelOrigin() + "/v1"),
                        // The packaged OpenAI-compatible server is local and needs no API credential.
                        Map.entry("spring.ai.openai.chat.temperature", ""),
                        Map.entry("spring.ai.openai.embedding.model", connection.embeddingModel()),
                        Map.entry("spring.ai.vectorstore.milvus.collectionName", connection.collectionName()),
                        Map.entry("spring.ai.vectorstore.milvus.embeddingDimension", connection.embeddingDimension()),
                        Map.entry("rag.document.storage-dir", root.resolve("documents").toString()),
                        Map.entry("rag.document-task.output-dir", store.get().outputDirectory()),
                        Map.entry("rag.page.storage-dir", root.resolve("pages").toString()),
                        Map.entry("rag.curl.storage-dir", root.resolve("curl").toString()),
                        Map.entry("spring.servlet.multipart.location", Files.createDirectories(root.resolve("uploads")).toString())
                )));
            } catch (IOException | IllegalArgumentException exception) {
                throw new IllegalStateException("无法加载 Roch 桌面配置。", exception);
            }
        };
    }

    static Path requiredPath(String name) {
        String value = System.getenv(name);
        if (value == null || !Path.of(value).isAbsolute()) {
            throw new IllegalStateException("Roch 启动器未提供有效的 " + name + "。");
        }
        return Path.of(value).normalize();
    }

    @Bean
    DesktopChatOptions desktopChatOptions(DesktopSettingsStore store) {
        return new DesktopChatOptions(store);
    }

    @Bean
    DesktopSessionFilter desktopSessionFilter(DesktopActivity activity, DesktopSettingsStore store,
                                               ConfigurableApplicationContext context) {
        return new DesktopSessionFilter(System.getenv("ROCH_DESKTOP_TOKEN"), activity, store, context::close);
    }

    @EventListener
    void signalReady(ApplicationReadyEvent event) throws IOException {
        var context = (ServletWebServerApplicationContext) event.getApplicationContext();
        Path target = requiredPath("ROCH_DESKTOP_READY_FILE");
        Path pending = Files.createTempFile(target.getParent(), "server-ready-", ".tmp");
        try {
            Files.writeString(pending, Integer.toString(context.getWebServer().getPort()));
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    @Controller
    @Profile("desktop")
    static class DesktopRoutes {
        @GetMapping({"/dashboard", "/chat", "/conversation", "/search", "/import", "/documents",
                "/reports", "/webpage", "/settings"})
        String app(HttpServletResponse response) {
            response.setHeader("Cache-Control", "no-store");
            return "forward:/index.html";
        }
    }
}
