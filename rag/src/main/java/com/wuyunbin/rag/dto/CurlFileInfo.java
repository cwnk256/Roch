package com.wuyunbin.rag.dto;

import java.time.LocalDateTime;

/**
 * 已保存 JSON 文件的概要信息（列表项，不含文件内容）。
 *
 * @param fileName     文件名
 * @param sizeBytes    文件大小（字节）
 * @param lastModified 最后修改时间
 */
public record CurlFileInfo(
        String fileName,
        long sizeBytes,
        LocalDateTime lastModified
) {
}
