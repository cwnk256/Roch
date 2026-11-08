package com.wuyunbin.rag.service.page;

import com.wuyunbin.rag.service.HtmlToMarkdownConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.UnknownHostException;

/**
 * Jsoup 直连抓取引擎：拉取 HTML（自动探测 GBK/UTF-8 编码）→ {@link HtmlToMarkdownConverter} 转 Markdown。
 *
 * <p>零外部依赖、零成本，适合静态服务端渲染页面；SPA/强反爬页面可能返回空正文，
 * 由上层在 auto 模式下回退 {@link FirecrawlPageFetchEngine}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsoupPageFetchEngine implements PageFetchEngine {

    /**
     * 模拟浏览器 UA，降低被站点以 403/验证页拒绝的概率。
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final int TIMEOUT_MS = 15000;

    private final HtmlToMarkdownConverter converter;

    @Override
    public String name() {
        return "jsoup";
    }

    @Override
    public PageContent fetch(String url) {
        Document document = connect(url);
        String title = document.title() == null ? "" : document.title().strip();
        String markdown = converter.convert(document, url);
        return new PageContent(title, markdown);
    }

    /**
     * Jsoup 直连：自动跟随重定向、解压 gzip、按 HTTP 头/meta/字节流探测编码（兼容 GBK）。
     */
    private Document connect(String url) {
        try {
            log.info("[PageFetch:jsoup] 抓取网页，URL: {}", url);
            return Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .timeout(TIMEOUT_MS)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    .get();
        } catch (HttpStatusException e) {
            log.warn("[PageFetch:jsoup] 目标页面返回错误状态码，URL: {}, 状态码: {}", url, e.getStatusCode());
            throw new IllegalArgumentException("目标页面返回 HTTP " + e.getStatusCode(), e);
        } catch (IllegalArgumentException e) {
            // Jsoup 对非法 URL 抛 IllegalArgumentException
            throw new IllegalArgumentException("URL 格式不合法: " + e.getMessage(), e);
        } catch (UnknownHostException e) {
            log.warn("[PageFetch:jsoup] DNS 解析失败，URL: {}", url);
            throw new RuntimeException("无法解析域名，请检查 URL 是否正确: " + e.getMessage(), e);
        } catch (java.net.SocketTimeoutException e) {
            log.warn("[PageFetch:jsoup] 连接超时，URL: {}", url);
            throw new RuntimeException("连接超时（15s），目标站点可能无响应或网络不可达", e);
        } catch (UncheckedIOException | IOException e) {
            log.error("[PageFetch:jsoup] 网页抓取失败（连接/IO），URL: {}", url, e);
            throw new RuntimeException("网页抓取失败: " + e.getMessage(), e);
        }
    }
}
