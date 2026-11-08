package com.wuyunbin.rag.service.generation.model;

import java.util.List;

/**
 * 已生成的单个章节：标题 + 正文 + 本章节使用的证据列表。
 *
 * <p>正文只由 {@code SectionGenerator} 基于检索证据写出；证据与正文同时产出，
 * 不在全文生成后反向猜测来源。</p>
 */
public record GeneratedSection(

        String title,

        String content,

        List<Evidence> evidences
) {
}
