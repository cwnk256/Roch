package com.wuyunbin.rag.service.page;

/**
 * 网页抓取引擎抽象：不同实现负责"URL → 正文 Markdown"。
 *
 * <p>当前实现：{@link JsoupPageFetchEngine}（本地直连，零外部依赖）
 * 与 {@link FirecrawlPageFetchEngine}（Firecrawl 云 API，支持 JS 渲染与反爬）。</p>
 */
public interface PageFetchEngine {

    /**
     * 引擎标识，对应配置/请求参数 rag.page.engine：jsoup / firecrawl。
     */
    String name();

    /**
     * 抓取并转换页面。
     *
     * @throws IllegalArgumentException URL 非法或上游返回明确错误状态码
     * @throws RuntimeException         网络不可达、超时、响应解析失败等
     */
    PageContent fetch(String url);
}
