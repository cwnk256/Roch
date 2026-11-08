package com.wuyunbin.rag.tool;

import com.wuyunbin.rag.dto.PageResponse;
import com.wuyunbin.rag.service.PageService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * WebPageTools 单测：成功返回（含截断）、异常转友好文本，不发起真实网络请求。
 */
class WebPageToolsTest {

    private final PageService pageService = mock(PageService.class);

    private PageResponse response(String markdown) {
        return new PageResponse("https://a.test", "标题", "f.txt", "/tmp/f.txt",
                LocalDateTime.now(), markdown.length(), markdown);
    }

    @Test
    void fetchWebPage_returnsTitleAndMarkdown_andUsesAutoEngine() {
        when(pageService.fetchPage("https://a.test", "auto")).thenReturn(response("# 正文"));

        String result = new WebPageTools(pageService, 12000).fetchWebPage("https://a.test");

        assertTrue(result.contains("标题"));
        assertTrue(result.contains("# 正文"));
        verify(pageService).fetchPage("https://a.test", "auto");
    }

    @Test
    void fetchWebPage_truncatesLongContent() {
        String longMarkdown = "A".repeat(1000);
        when(pageService.fetchPage(anyString(), eq("auto"))).thenReturn(response(longMarkdown));

        String result = new WebPageTools(pageService, 100).fetchWebPage("https://a.test");

        assertTrue(result.contains("已截断") || result.contains("过长"));
        // 100 字符正文 + 前缀与提示，整体不应接近原文 1000 字符
        assertTrue(result.length() < 600);
    }

    @Test
    void fetchWebPage_blankUrl_returnsFriendlyErrorWithoutCallingService() {
        String result = new WebPageTools(pageService, 12000).fetchWebPage("  ");

        assertTrue(result.contains("失败"));
        verifyNoInteractions(pageService);
    }

    @Test
    void fetchWebPage_serviceThrows_returnsFriendlyError() {
        when(pageService.fetchPage(anyString(), eq("auto")))
                .thenThrow(new RuntimeException("连接超时"));

        String result = new WebPageTools(pageService, 12000).fetchWebPage("https://down.test");

        assertTrue(result.contains("连接超时"));
    }
}
