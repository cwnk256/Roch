package com.wuyunbin.rag.service;

import com.wuyunbin.rag.dto.PageResponse;
import com.wuyunbin.rag.service.page.FirecrawlPageFetchEngine;
import com.wuyunbin.rag.service.page.JsoupPageFetchEngine;
import com.wuyunbin.rag.service.page.PageContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Set;

/**
 * 网页抓取服务：按配置/请求选择引擎拉取正文 Markdown → 落盘 .txt。
 * 落盘文件可直接经 TXT 导入接口进入 RAG 切片管线。
 *
 * <p>引擎模式（rag.page.engine，可被请求参数覆盖）：</p>
 * <ul>
 *     <li>jsoup：本地直连，零外部依赖，静态页面首选</li>
 *     <li>firecrawl：Firecrawl 云 API，JS 渲染 + 反爬，按页计费</li>
 *     <li>auto（默认）：先走 jsoup；正文为空或抓取失败时，若配置了 Firecrawl Key 则自动回退</li>
 * </ul>
 */
@Slf4j
@Service
public class PageService {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Set<String> SUPPORTED_ENGINES = Set.of("jsoup", "firecrawl", "auto");

    private final JsoupPageFetchEngine jsoupEngine;
    private final FirecrawlPageFetchEngine firecrawlEngine;
    private final String defaultEngine;

    public PageService(JsoupPageFetchEngine jsoupEngine,
                       FirecrawlPageFetchEngine firecrawlEngine,
                       @Value("${rag.page.engine:auto}") String defaultEngine) {
        this.jsoupEngine = jsoupEngine;
        this.firecrawlEngine = firecrawlEngine;
        this.defaultEngine = normalizeEngine(defaultEngine);
    }

    @Value("${rag.page.storage-dir:data/pages}")
    private String storageDir;

    public PageResponse fetchPage(String url) {
        return fetchPage(url, null);
    }

    /**
     * @param engineOverride 请求级引擎覆盖（jsoup/firecrawl/auto）；为空走全局配置
     */
    public PageResponse fetchPage(String url, String engineOverride) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("请求URL不能为空");
        }
        String engine = normalizeEngine(engineOverride == null || engineOverride.isBlank()
                ? defaultEngine : engineOverride);

        PageContent content = switch (engine) {
            case "firecrawl" -> firecrawlEngine.fetch(url);
            case "jsoup" -> requireNonBlank(jsoupEngine.fetch(url));
            default -> fetchWithAutoFallback(url);
        };

        if (content.markdown() == null || content.markdown().isBlank()) {
            throw new IllegalStateException("页面正文抽取结果为空（可能是需要 JS 渲染的页面，可改用增强抓取）");
        }
        return saveToFile(url, content.title(), content.markdown());
    }

    /**
     * auto 模式：jsoup 优先；正文为空或抛错时，在已配置 Firecrawl Key 的前提下回退云端重试。
     */
    private PageContent fetchWithAutoFallback(String url) {
        RuntimeException jsoupError = null;
        PageContent content = null;
        try {
            content = jsoupEngine.fetch(url);
        } catch (RuntimeException e) {
            jsoupError = e;
            log.info("[PageFetch] jsoup 抓取失败，准备评估 Firecrawl 回退，URL: {}, 原因: {}", url, e.getMessage());
        }

        boolean jsoupEmpty = content == null || content.markdown() == null || content.markdown().isBlank();
        if (!jsoupEmpty) {
            return content;
        }
        if (!firecrawlEngine.isConfigured()) {
            if (jsoupError != null) {
                throw jsoupError;
            }
            return content;
        }
        log.info("[PageFetch] jsoup 正文为空/失败，回退 Firecrawl 云端抓取，URL: {}", url);
        PageContent firecrawlContent = firecrawlEngine.fetch(url);
        if ((firecrawlContent.markdown() == null || firecrawlContent.markdown().isBlank()) && jsoupError != null) {
            // 双引擎均失败时保留 jsoup 的原始网络错误，便于用户定位
            throw jsoupError;
        }
        return firecrawlContent;
    }

    private PageContent requireNonBlank(PageContent content) {
        if (content.markdown() == null || content.markdown().isBlank()) {
            throw new IllegalStateException("页面正文抽取结果为空（可能是需要 JS 渲染的页面，可改用增强抓取）");
        }
        return content;
    }

    private static String normalizeEngine(String engine) {
        String normalized = engine == null ? "auto" : engine.trim().toLowerCase();
        if (!SUPPORTED_ENGINES.contains(normalized)) {
            throw new IllegalArgumentException("不支持的抓取引擎: " + engine
                    + "（可选值：jsoup / firecrawl / auto）");
        }
        return normalized;
    }

    private PageResponse saveToFile(String url, String title, String markdown) {
        try {
            Path dir = Paths.get(storageDir);
            Files.createDirectories(dir);

            String urlHash = sha256Hex(url).substring(0, 8);
            String urlWithoutProtocol = url.replaceFirst("^https?://", "");
            // 清洗 URL 派生片段：把路径分隔符与 Windows 非法文件名字符替换为 "-"，避免被解析成嵌套目录
            String safeUrl = urlWithoutProtocol.replaceAll("[/\\\\:*?\"<>|]", "-");
            String fileName = "-page-" + safeUrl + LocalDateTime.now().format(FILE_TS) + "-" + urlHash + ".txt";
            Path file = dir.resolve(fileName);
            Files.writeString(file, markdown, StandardCharsets.UTF_8);
            log.info("网页 Markdown 已保存: {}（{} 字符）", file.toAbsolutePath(), markdown.length());

            return new PageResponse(url, title, fileName, file.toAbsolutePath().toString(),
                    LocalDateTime.now(), markdown.length(), markdown);
        } catch (IOException e) {
            log.error("Markdown 文件写入失败，URL: {}, 目录: {}", url, storageDir, e);
            throw new RuntimeException("文件写入失败: " + e.getMessage(), e);
        }
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
