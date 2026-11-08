package com.wuyunbin.rag.dto;

/**
 * 文档任务状态。
 *
 * <p>生命周期：{@link #PENDING} → {@link #RUNNING} → {@link #COMPLETED} / {@link #FAILED}。</p>
 *
 * <p>Phase 2 第一版为同步执行：任务创建后立即执行并在同一请求内返回终态，
 * 但状态模型按异步任务设计，后续接入任务队列时无需修改 API 模型。</p>
 */
public enum DocumentTaskStatus {

    /**
     * 已创建，尚未开始执行。
     */
    PENDING,

    /**
     * 正在执行（检索 + 生成 + 导出）。
     */
    RUNNING,

    /**
     * 执行成功，结果文件已落盘。
     */
    COMPLETED,

    /**
     * 执行失败， 记录原因。
     */
    FAILED
}
