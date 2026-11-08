package com.wuyunbin.rag.service.task;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文档任务相关配置项。
 *
 * <p>检索参数（top-k / similarity-threshold）不在此重复定义，
 * 统一复用 {@code rag.chat.rag.top-k} / {@code rag.chat.rag.similarity-threshold}。</p>
 */
@Getter
@Setter
@Component
public class DocumentTaskProperties {

    /**
     * 生成 PDF 的落盘目录（相对启动工作目录或绝对路径）。
     */
    @Value("${rag.document-task.output-dir:data/reports}")
    private String outputDir;

    /**
     * 单个章节拼给 LLM 的证据上下文最大字符数（简单上下文控制，非精确 token 估算）。
     */
    @Value("${rag.document-task.max-context-chars:24000}")
    private int maxContextChars;

    /**
     * 中文字体资源路径。支持 Spring Resource 前缀：
     * 默认从 classpath 加载随包分发的开源中文字体；
     * 也可配置为 file:/path/to/font.ttf 等外部路径。
     * 不硬编码操作系统字体目录。
     */
    @Value("${rag.document-task.font-path:classpath:fonts/NotoSansSC-Regular.ttf}")
    private String fontPath;

    /**
     * 文档任务开关（预留）。
     */
    @Value("${rag.document-task.enabled:true}")
    private boolean enabled;
}
