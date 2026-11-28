package com.wuyunbin.rag.service.ocr;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
class OcrEngineClient {
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Gson gson = new Gson();
    private final URI healthEndpoint;
    private final URI recognizeEndpoint;
    private final Duration pageTimeout;

    OcrEngineClient(@Value("${rag.ocr.base-url:http://127.0.0.1:18765}") String baseUrl,
                    @Value("${rag.ocr.page-timeout-seconds:180}") long pageTimeoutSeconds) {
        URI base = URI.create(baseUrl);
        String host = base.getHost() == null ? "" : base.getHost().toLowerCase(Locale.ROOT);
        if (!"http".equalsIgnoreCase(base.getScheme()) || !LOCAL_HOSTS.contains(host)
                || base.getRawUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null
                || base.getPort() == 0 || base.getPort() > 65535) {
            throw new IllegalArgumentException("OCR 服务必须使用本机回环 HTTP 地址（localhost、127.0.0.1 或 [::1]）");
        }
        // 固定 localhost 的目的地址，避免代理或本机域名配置将页面图像转发到外部。
        String loopback = "localhost".equals(host) ? "127.0.0.1" : host;
        String port = base.getPort() == -1 ? "" : ":" + base.getPort();
        String endpoint = "http://" + loopback + port + base.getRawPath().replaceAll("/+$", "");
        healthEndpoint = URI.create(endpoint + "/health");
        recognizeEndpoint = URI.create(endpoint + "/v1/recognize");
        if (pageTimeoutSeconds < 1) {
            throw new IllegalArgumentException("OCR 页面识别超时必须为正数");
        }
        pageTimeout = Duration.ofSeconds(pageTimeoutSeconds);
    }

    void requireReady() {
        var request = HttpRequest.newBuilder(healthEndpoint).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json").GET().build();
        var response = send(request, "检查 OCR 服务超时");
        if (response.statusCode() != 200) {
            throw new EngineFailure("OCR 服务未就绪，请启动 OCR 服务后重试（HTTP " + response.statusCode() + "）");
        }
        try {
            Health health = gson.fromJson(response.body(), Health.class);
            if (health != null && "ready".equals(health.status())) {
                return;
            }
        } catch (JsonParseException ignored) {
            // 健康检查格式不匹配时与未就绪采用相同的恢复路径。
        }
        throw new EngineFailure("OCR 服务未就绪，请启动 OCR 服务后重试");
    }

    OcrPage recognize(byte[] png) {
        var request = HttpRequest.newBuilder(recognizeEndpoint).timeout(pageTimeout)
                .header("Accept", "application/json").header("Content-Type", "image/png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(png)).build();
        var response = send(request, "OCR 页面识别超时，请重试");
        if (response.statusCode() != 200) {
            // 服务返回内容可能包含识别原文，状态消息仅使用已知的错误类型。
            String message = switch (response.statusCode()) {
                case 400, 415 -> "OCR 服务无法读取页面图像";
                case 413 -> "页面图像超过 OCR 服务的大小限制";
                case 429 -> "OCR 服务繁忙，请稍后重试";
                case 503 -> "OCR 服务未就绪，请启动 OCR 服务后重试";
                default -> "OCR 服务识别失败，请查看 OCR 服务状态后重试";
            };
            throw new EngineFailure(message + "（HTTP " + response.statusCode() + "）");
        }
        try {
            OcrPage page = gson.fromJson(response.body(), OcrPage.class);
            if (page != null && page.engineVersion() != null && !page.engineVersion().isBlank()
                    && page.blocks() != null && page.tables() != null) {
                return page;
            }
        } catch (RuntimeException ignored) {
            // 识别结果无法解析时交由任务记录失败页。
        }
        throw new EngineFailure("OCR 服务返回了无法解析的识别结果");
    }

    private HttpResponse<String> send(HttpRequest request, String timeoutMessage) {
        var exchange = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            // 同时约束接收响应正文的时间，避免服务耗尽单个识别线程。
            return exchange.get(request.timeout().orElseThrow().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw new EngineFailure(timeoutMessage, e);
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new EngineFailure("OCR 任务已中断", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpTimeoutException) {
                throw new EngineFailure(timeoutMessage, e.getCause());
            }
            throw new EngineFailure("无法连接 OCR 服务，请检查服务是否已启动后重试", e.getCause());
        }
    }

    private record Health(String status) {
    }

    static final class EngineFailure extends IllegalStateException {
        EngineFailure(String message) {
            super(message);
        }

        EngineFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
