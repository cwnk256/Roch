package com.wuyunbin.rag.tool;

import com.wuyunbin.rag.dto.PageResponse;
import com.wuyunbin.rag.service.PageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 网页读取工具：用户在多轮问答中给出网址时，模型调用本工具抓取网页正文，
 * 并以返回的 Markdown 作为回答依据。
 *
 * <p>内部复用 {@link PageService} 的 auto 模式：优先本地 Jsoup 直连（零成本），
 * 正文为空或失败时自动回退 Firecrawl 云端渲染（需配置 API Key）。</p>
 *
 * <p>抓取结果同时会落盘到 rag.page.storage-dir，便于审计与后续导入知识库；
 * 为避免超出模型上下文，返回给模型的正文按 rag.chat.tool.webpage.max-chars 截断。</p>
 */
@Slf4j
@Component
public class WebPageTools {

    private final PageService pageService;
    private final int maxChars;

    public WebPageTools(PageService pageService,
                        @Value("${rag.chat.tool.webpage.max-chars:12000}") int maxChars) {
        this.pageService = pageService;
        this.maxChars = maxChars;
    }

    /**
     * 抓取网页正文并返回 Markdown。
     *
     * @param url 完整网址，必须以 http:// 或 https:// 开头
     * @return 网页标题与正文 Markdown（超长截断）；失败时返回可读的错误说明
     */
    @Tool(description = "读取并抓取用户给出的网址（http/https 链接）对应的网页正文，返回该网页的 Markdown 内容，"
            + "自动处理需要 JavaScript 渲染的动态页面与反爬站点。"
            + "当用户消息中包含网址，并希望了解、总结该网页内容，或要求依据该网页回答问题时，必须调用此工具，"
            + "不要凭空猜测网页内容。参数 url 是完整网址。")
    public String fetchWebPage(String url) {
        log.info("Tool fetchWebPage called: url={}", url);
        if (url == null || url.isBlank()) {
            return "网页抓取失败：网址为空";
        }
        try {
            PageResponse response = pageService.fetchPage(url.trim(), "auto");
            String markdown = response.markdown() == null ? "" : response.markdown();
            String totalNote = "";
            if (markdown.length() > maxChars) {
                totalNote = "\n\n[网页内容过长，仅返回前 " + maxChars + " 字符，全文共 "
                        + markdown.length() + " 字符，已保存到服务器：" + response.fileName() + "]";
                markdown = markdown.substring(0, maxChars);
            }
            String title = response.title() == null || response.title().isBlank() ? "（无标题）" : response.title();
            return "网页标题：" + title + "\n网址：" + response.url() + "\n\n网页正文（Markdown）：\n"
                    + markdown + totalNote;
        } catch (Exception e) {
            log.warn("Tool fetchWebPage failed: url={}, reason={}", url, e.getMessage());
            return "网页抓取失败：" + e.getMessage()
                    + "。如网址需要登录或为内网地址，可能无法抓取，请基于已知信息向用户说明。";
        }
    }
}
