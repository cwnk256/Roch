package com.wuyunbin.rag.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 检索日志写入器：将检索记录序列化为 JSONL 单行，写入独立文件。
 * 日志失败绝不影响主链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalLogWriter {

    private static final Logger RETRIEVAL_LOG = LoggerFactory.getLogger("rag.retrieval");

    private final JsonMapper jsonMapper;
    private final RetrievalLogProperties properties;
    private final RetrievalLogStore retrievalLogStore;

    /**
     * 写入一条检索日志（同时写 JSONL 文件和内存缓冲）。
     * 关闭开关时不构造、不写盘；写盘失败不影响内存缓冲。
     */
    public void write(RetrievalLogRecord record) {
        if (!properties.isLogEnabled()) {
            return;
        }
        // 内存缓冲始终写入（查询接口依赖），不受文件写入异常影响
        try {
            retrievalLogStore.save(record);
        } catch (RuntimeException e) {
            log.warn("检索日志内存写入失败 logId={}", record.logId(), e);
        }
        try {
            RETRIEVAL_LOG.info(jsonMapper.writeValueAsString(record));
        } catch (RuntimeException e) {
            // Jackson 3 异常为非受检；日志失败只告警，绝不向主链路抛出
            log.warn("检索日志文件写入失败 logId={}", record.logId(), e);
        }
    }
}
