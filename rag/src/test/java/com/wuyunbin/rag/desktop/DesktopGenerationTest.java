package com.wuyunbin.rag.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

class DesktopGenerationTest {
    @TempDir
    Path directory;

    @Test
    void sendsFrozenReportOptionsAndThenCurrentChatOptions() throws Exception {
        var mapper = new ObjectMapper();
        var requests = new LinkedBlockingQueue<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                byte[] response = """
                        {"id":"completion","created":0,"object":"chat.completion","model":"stub",
                        "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"OK"}}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            var file = directory.resolve("settings.json");
            var initial = new DesktopSettings("report-model", 0.4, 0.8, 512, directory.resolve("旧 报告").toString(),
                    origin, "embedding", 1024);
            mapper.writeValue(file.toFile(), initial);
            var store = new DesktopSettingsStore(file, mapper);
            var frozen = store.generationSnapshot();
            store.save(new DesktopSettings("chat-model", null, null, null, directory.resolve("新 报告").toString(),
                    origin, "embedding", 1024));
            var model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                    .apiKey("local").baseUrl(origin + "/v1").model("default").build()).build();
            var client = ChatClient.builder(model).defaultAdvisors(new DesktopChatOptions(store))
                    .defaultTools(new ExampleTools()).build();

            assertEquals("OK", DesktopChatOptions.withReportSnapshot(frozen, () -> client.prompt("report").call().content()));
            assertEquals("OK", client.prompt("chat").call().content());
            var report = requests.remove();
            assertEquals("report-model", report.path("model").asText());
            assertEquals(0.4, report.path("temperature").asDouble());
            assertEquals(0.8, report.path("top_p").asDouble());
            assertEquals(512, report.path("max_tokens").asInt());
            var chat = requests.remove();
            assertEquals("chat-model", chat.path("model").asText());
            assertFalse(chat.has("temperature"));
            assertFalse(chat.has("top_p"));
            assertFalse(chat.has("max_tokens"));
            assertEquals("sample", chat.path("tools").path(0).path("function").path("name").asText());
        } finally {
            server.stop(0);
        }
    }

    static final class ExampleTools {
        @Tool(description = "A sample tool")
        public String sample() {
            return "OK";
        }
    }
}
