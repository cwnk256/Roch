package com.wuyunbin.rag.retrieval;

import java.util.List;
import java.util.Optional;

/**
 * 检索日志存储接口。
 * 内存实现仅保留最近 N 条，重启即失；非审计日志。
 */
public interface RetrievalLogStore {

    /**
     * 保存一条检索日志。
     */
    void save(RetrievalLogRecord record);

    /**
     * 按会话 ID 查询，倒序返回（最新在前）。
     *
     * @param conversationId 会话 ID，为 null 时返回全部
     * @param limit          返回条数上限
     */
    List<RetrievalLogRecord> findByConversationId(String conversationId, int limit);

    /**
     * 按 logId 查询单条。
     */
    Optional<RetrievalLogRecord> findByLogId(String logId);
}
