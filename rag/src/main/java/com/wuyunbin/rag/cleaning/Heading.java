package com.wuyunbin.rag.cleaning;

/**
 * Markdown ATX 标题：级别（1~6）与标题文本。
 *
 * @param level 标题级别，对应井号数量
 * @param title 去掉装饰性井号后的标题文本
 */
public record Heading(int level, String title) {
}
