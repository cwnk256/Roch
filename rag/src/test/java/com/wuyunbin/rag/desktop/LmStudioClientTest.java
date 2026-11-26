package com.wuyunbin.rag.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LmStudioClientTest {
    @Test
    void distinguishesModelTypesAndVerifiesTheirActualResponses() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mapper = new ObjectMapper();
        route(server, "/api/v1/models", """
                {"models":[{"type":"llm","key":"sample","display_name":"Test model","loaded_instances":[{"id":"loaded-model"}]},
                {"type":"embedding","key":"emb","loaded_instances":[]},{"type":"decision","key":"excluded"}]}
                """);
        route(server, "/v1/embeddings", "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}");
        route(server, "/v1/chat/completions", mapper.writeValueAsString(java.util.Map.of("choices", List.of(
                java.util.Map.of("message", java.util.Map.of("content", "{\"ok\":true}"))))));
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new LmStudioClient(mapper);
            assertEquals(List.of(
                    new LmStudioClient.Model("loaded-model", "Test model", "llm", true),
                    new LmStudioClient.Model("emb", "emb", "embedding", false)), client.models(origin));
            assertEquals(3, client.test(origin, "emb", "embedding").dimension());
            assertNull(client.test(origin, "loaded-model", "llm").dimension());
        } finally {
            server.stop(0);
        }
    }

    private void route(HttpServer server, String path, String content) {
        server.createContext(path, exchange -> {
            try (exchange) {
                byte[] data = content.getBytes(StandardCharsets.UTF_8);
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
            }
        });
    }
}
