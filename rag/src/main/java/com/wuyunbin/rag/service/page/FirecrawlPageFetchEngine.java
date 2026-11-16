package com.wuyunbin.rag.service.page;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Firecrawl 云 API 抓取引擎：POST {base-url}/v1/scrape，获取云端渲染后的干净 Markdown。
 *
 * <p>相比本地 Jsoup 直连，Fire-engine 提供无头浏览器 JS 渲染与代理反爬能力，
 * 适合 SPA 页面与封锁严格的站点。按页计费（scrape 1 credit/页）。</p>
 *
 * <p>API Key 通过 rag.page.firecrawl.api-key 或环境变量 FIRECRAWL_API_KEY 注入，
 * 缺失时不创建请求，直接抛出可读的配置提示。</p>
 *
 * <p>响应体先以 String 接收、再用统一 {@link ObjectMapper} 解析，避免 RestTemplate
 * 直接反序列化抽象 {@link JsonNode} 时的 "Type definition error"。</p>
 */
@Slf4j
@Component
public class FirecrawlPageFetchEngine implements PageFetchEngine {

    private static final String SCRAPE_PATH = "/v1/scrape";

    private final String apiKey;
    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public FirecrawlPageFetchEngine(
            ObjectMapper objectMapper,
            @Value("${rag.page.firecrawl.api-key:}") String apiKey,
            @Value("${rag.page.firecrawl.base-url:https://api.firecrawl.dev}") String baseUrl,
            @Value("${rag.page.firecrawl.timeout-ms:30000}") int timeoutMs) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public String name() {
        return "firecrawl";
    }

    /**
     * Key 是否可用：auto 模式据此决定是否允许回退。
     */
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    @Override
    public PageContent fetch(String url) {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Firecrawl API Key 未配置：请在 application.properties 填写 rag.page.firecrawl.api-key，"
                            + "或设置环境变量 FIRECRAWL_API_KEY 后重试");
        }
        log.info("[PageFetch:firecrawl] 云端抓取网页，URL: {}", url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        Map<String, Object> body = Map.of(
                "url", url,
                "formats", List.of("markdown"));

        String raw;
        try {
            // 先以字符串接收，再自行用统一 ObjectMapper 解析，规避 JsonNode 抽象类型反序列化问题
            raw = restTemplate.postForObject(baseUrl + SCRAPE_PATH,
                    new HttpEntity<>(body, headers), String.class);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            log.warn("[PageFetch:firecrawl] 鉴权失败，状态码: {}", e.getStatusCode());
            throw new IllegalStateException(
                    "Firecrawl 鉴权失败（" + e.getStatusCode() + "）：请检查 API Key 是否正确、额度是否用尽", e);
        } catch (HttpClientErrorException e) {
            String detail = extractError(e.getResponseBodyAsString());
            log.warn("[PageFetch:firecrawl] 上游返回错误，状态码: {}, 详情: {}", e.getStatusCode(), detail);
            throw new RuntimeException("Firecrawl 抓取失败（" + e.getStatusCode() + "）" + detail, e);
        } catch (ResourceAccessException e) {
            log.error("[PageFetch:firecrawl] 网络不可达/超时，URL: {}", url, e);
            throw new RuntimeException("Firecrawl 服务连接失败或超时：" + e.getMessage(), e);
        }

        if (raw == null || raw.isBlank()) {
            throw new RuntimeException("Firecrawl 返回空响应");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            log.error("[PageFetch:firecrawl] 响应不是合法 JSON，前 200 字符: {}",
                    raw.length() > 200 ? raw.substring(0, 200) : raw, e);
            throw new RuntimeException("Firecrawl 返回内容无法解析为 JSON", e);
        }

        // 失败响应形如 {"success":false,"error":"..."}，即使 HTTP 200 也需拦截
        JsonNode errorNode = root.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            String message = errorNode.isTextual() ? errorNode.asText() : errorNode.path("message").asText();
            throw new RuntimeException("Firecrawl 抓取失败：" + (message.isBlank() ? errorNode.toString() : message));
        }

        JsonNode data = root.path("data");
        String markdown = data.path("markdown").asText("");
        String title = data.path("metadata").path("title").asText("");
        if (title.isBlank()) {
            title = data.path("title").asText("");
        }
        // 与 HtmlToMarkdownConverter 输出保持一致：末尾附注来源 URL
        if (!markdown.isBlank()) {
            markdown = markdown.strip() + "\n\n---\n> 来源: " + url + "\n";
        }
        return new PageContent(title.strip(), markdown);
    }

    private String extractError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            // Firecrawl 错误体形如 {"error":"..."} 或 {"error":{"message":"..."}}
            JsonNode error = node.path("error");
            if (error.isTextual()) {
                return "：" + error.asText();
            }
            JsonNode message = error.path("message");
            if (message.isTextual()) {
                return "：" + message.asText();
            }
        } catch (Exception ignored) {
            // 错误体非 JSON 时原样截断返回
        }
        String trimmed = responseBody.strip();
        return "：" + (trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed);
    }
}
