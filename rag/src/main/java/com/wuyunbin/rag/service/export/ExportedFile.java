package com.wuyunbin.rag.service.export;

import java.nio.file.Path;

/**
 * 导出结果文件：用户可读文件名 + 落盘路径 + MIME 类型。
 */
public record ExportedFile(

        String fileName,

        Path path,

        String contentType
) {
}
