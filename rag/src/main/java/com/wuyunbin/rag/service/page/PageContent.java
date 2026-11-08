package com.wuyunbin.rag.service.page;

/**
 * 网页抓取引擎的统一返回：页面标题与正文 Markdown。
 *
 * @param title    页面标题（可能为空串）
 * @param markdown 正文 Markdown（保留标题层级，可直接进入 heading-aware 切片管线）
 */
public record PageContent(String title, String markdown) {
}
