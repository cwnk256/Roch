package com.wuyunbin.rag.cleaning;

/**
 * 文档分段类型：普通正文、Markdown 表格、代码块。
 */
public enum SegmentType {

    /**
     * 普通正文（含标题、列表、段落），走语义切片。
     */
    PROSE,

    /**
     * Markdown 表格（连续 | 行，含分隔行），原子块入库。
     */
    TABLE,

    /**
     * 围栏代码块（``` 或 ~~~），保持原样、原子块入库。
     */
    CODE
}
 