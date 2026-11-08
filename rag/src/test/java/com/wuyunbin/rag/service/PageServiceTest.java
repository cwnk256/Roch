package com.wuyunbin.rag.service;

import com.wuyunbin.rag.dto.PageResponse;
import com.wuyunbin.rag.service.page.FirecrawlPageFetchEngine;
import com.wuyunbin.rag.service.page.JsoupPageFetchEngine;
import com.wuyunbin.rag.service.page.PageContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * PageService 引擎编排单测：auto 回退、显式引擎选择与落盘，不发起真实网络请求。
 */
class PageServiceTest {

    private JsoupPageFetchEngine jsoup;
    private FirecrawlPageFetchEngine firecrawl;
    private PageService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        jsoup = mock(JsoupPageFetchEngine.class);
        firecrawl = mock(FirecrawlPageFetchEngine.class);
        service = new PageService(jsoup, firecrawl, "auto");
        ReflectionTestUtils.setField(service, "storageDir", tempDir.toString());
    }

    @Test
    void auto_returnsJsoupContentWithoutCallingFirecrawl_whenJsoupSucceeds() {
        when(jsoup.fetch("https://a.test")).thenReturn(new PageContent("标题", "# 正文\n\n---\n> 来源: x"));
        when(firecrawl.isConfigured()).thenReturn(true);

        PageResponse response = service.fetchPage("https://a.test", null);

        assertEquals("标题", response.title());
        assertTrue(response.length() > 0);
        verify(firecrawl, never()).fetch(anyString());
    }

    @Test
    void auto_fallsBackToFirecrawl_whenJsoupReturnsBlank() {
        when(jsoup.fetch("https://spa.test")).thenReturn(new PageContent("壳", ""));
        when(firecrawl.isConfigured()).thenReturn(true);
        when(firecrawl.fetch("https://spa.test"))
                .thenReturn(new PageContent("真实标题", "# 云端渲染正文"));

        PageResponse response = service.fetchPage("https://spa.test", "auto");

        assertEquals("真实标题", response.title());
        verify(firecrawl).fetch("https://spa.test");
    }

    @Test
    void auto_fallsBackToFirecrawl_whenJsoupThrows() {
        when(jsoup.fetch("https://blocked.test")).thenThrow(new RuntimeException("403"));
        when(firecrawl.isConfigured()).thenReturn(true);
        when(firecrawl.fetch("https://blocked.test"))
                .thenReturn(new PageContent("标题", "# 正文"));

        PageResponse response = service.fetchPage("https://blocked.test", "auto");

        assertTrue(response.markdown().contains("正文"));
        verify(firecrawl).fetch("https://blocked.test");
    }

    @Test
    void auto_withoutApiKey_rethrowsJsoupError() {
        when(jsoup.fetch("https://down.test")).thenThrow(new RuntimeException("DNS 失败"));
        when(firecrawl.isConfigured()).thenReturn(false);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.fetchPage("https://down.test", null));
        assertEquals("DNS 失败", ex.getMessage());
        verify(firecrawl, never()).fetch(anyString());
    }

    @Test
    void jsoupMode_doesNotCallFirecrawl_evenWhenBlank() {
        when(jsoup.fetch("https://blank.test")).thenReturn(new PageContent("t", " "));
        when(firecrawl.isConfigured()).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.fetchPage("https://blank.test", "jsoup"));
        verify(firecrawl, never()).fetch(anyString());
    }

    @Test
    void firecrawlMode_skipsJsoup() {
        when(firecrawl.fetch("https://fc.test")).thenReturn(new PageContent("t", "# 正文"));

        service.fetchPage("https://fc.test", "firecrawl");

        verify(jsoup, never()).fetch(anyString());
    }

    @Test
    void invalidEngine_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.fetchPage("https://a.test", "selenium"));
    }

    @Test
    void blankUrl_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> service.fetchPage("  ", null));
    }
}
