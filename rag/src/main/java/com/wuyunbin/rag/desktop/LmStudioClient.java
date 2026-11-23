package com.wuyunbin.rag.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@Component
@Profile("desktop")
final class LmStudioClient {
    private final ObjectMapper mapper;

    LmStudioClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    record Model(String id, String name, String type, boolean loaded) {}
    record TestResult(String message, Integer dimension) {}

    List<Model> models(String address) {
        JsonNode entries = call(address, "/api/v1/models", null).path("models");
        if (!entries.isArray()) throw new ResponseStatusException(BAD_GATEWAY, "LM Studio 未返回有效的模型列表。");

        List<Model> models = new ArrayList<>();
        for (JsonNode entry : entries) {
            String type = entry.path("type").asText();
            if (!type.equals("llm") && !type.equals("embedding")) continue;
            String id = entry.path("key").asText();
            if (id.isBlank()) continue;
            JsonNode instances = entry.path("loaded_instances");
            String name = entry.path("display_name").asText(id);
            if (instances.isEmpty()) {
                models.add(new Model(id, name, type, false));
            } else {
                for (JsonNode instance : instances) {
                    models.add(new Model(instance.path("id").asText(id), name, type, true));
                }
            }
        }
        return List.copyOf(models);
    }

    TestResult test(String address, String model, String type) {
        String modelId = DesktopSettings.modelId(model, false);
        if ("embedding".equals(type)) {
            var embedding = call(address, "/v1/embeddings", Map.of("model", modelId, "input", "Roch connection test"))
                    .path("data").path(0).path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                throw new ResponseStatusException(BAD_GATEWAY, "模型未返回嵌入向量。");
            }
            return new TestResult("嵌入模型可用。", embedding.size());
        }
        if (!"llm".equals(type)) throw new IllegalArgumentException("请指定生成模型或嵌入模型。");
        var schema = Map.of("type", "object", "properties", Map.of("ok", Map.of("type", "boolean")),
                "required", List.of("ok"), "additionalProperties", false);
        JsonNode response = call(address, "/v1/chat/completions", Map.of("model", modelId,
                "messages", List.of(Map.of("role", "user", "content", "Return a JSON object with ok set to true.")),
                "max_tokens", 512, "response_format", Map.of("type", "json_schema",
                        "json_schema", Map.of("name", "roch_test", "strict", true, "schema", schema))));
        try {
            String content = response.path("choices").path(0).path("message").path("content").asText();
            var result = mapper.readTree(content);
            if (result == null || !result.path("ok").isBoolean() || !result.path("ok").asBoolean()) {
                throw new IOException("invalid model output");
            }
            return new TestResult("生成模型可返回结构化结果。", null);
        } catch (IOException exception) {
            throw new ResponseStatusException(BAD_GATEWAY, "模型未返回有效的结构化结果，请确认模型支持 JSON Schema。");
        }
    }

    private JsonNode call(String address, String path, Map<String, ?> body) {
        String origin = DesktopSettings.normalizeOrigin(address);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(origin + path).toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(body == null ? 6000 : 90000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (var output = connection.getOutputStream()) {
                    mapper.writeValue(output, body);
                }
            }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) {
                throw new ResponseStatusException(BAD_GATEWAY, "LM Studio 已启用认证。首版请使用仅本机可访问免认证服务。");
            }
            if (status != 200) {
                throw new ResponseStatusException(BAD_GATEWAY, "LM Studio 返回 HTTP " + status + "。请检查服务、模型和加载状态。");
            }
            try (var input = connection.getInputStream()) {
                byte[] data = input.readNBytes(1_048_577);
                if (data.length > 1_048_576) throw new IOException("response too large");
                var json = mapper.readTree(data);
                if (json == null || !json.isObject()) throw new IOException("invalid response");
                return json;
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(BAD_GATEWAY, "无法读取本地 LM Studio 响应。请检查地址和服务器状态。");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
