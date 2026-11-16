package com.wuyunbin.rag.dto;

/**
 * 文档状态枚举：导入/重建/删除流程的状态机。
 */
public enum DocumentStatus {

    /**
     * 处理中（导入/重建过程中）
     */
    PROCESSING,

    /**
     * 就绪（切片已写入 Milvus）
     */
    READY,

    /**
     * 失败（导入或重建异常）
     */
    FAILED,

    /**
     * 已删除（Milvus 向量已删除）
     */
    DELETED
}
