package com.wuyunbin.rag.dto;

/**
 * @param url    目标 URL
 * @param engine 网页抓取引擎（仅 /page 使用）：jsoup / firecrawl / auto，为空走全局配置
 */
public record CurlRequest(String url, String engine) {

}
