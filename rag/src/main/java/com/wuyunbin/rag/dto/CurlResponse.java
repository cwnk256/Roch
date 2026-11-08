package com.wuyunbin.rag.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 外部网络请求结果：解析后的 JSON 数据与本地落盘信息。
 *
 * @param url      请求地址
 * @param fileName 保存的 JSON 文件名
 * @param filePath JSON 文件绝对路径，方便后续直接读取解析
 * @param savedAt  保存时间
 * @param data     解析后的 JSON 数据
 */
public record CurlResponse(
        String url,
        String fileName,
        String filePath,
        LocalDateTime savedAt,
        JsonNode data
) {
}
