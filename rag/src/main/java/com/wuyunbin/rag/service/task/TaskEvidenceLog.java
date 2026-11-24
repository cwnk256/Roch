package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.retrieval.RetrievalLogProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** 仅记录检索身份与耗时，证据正文留在原报告中。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskEvidenceLog {
    private static final org.slf4j.Logger AUDIT = LoggerFactory.getLogger("rag.retrieval");
    private final JsonMapper mapper;
    private final RetrievalLogProperties properties;

    public void event(String taskId, int section, int batch, String phase, String outcome,
                      int candidateCount, List<RetrievedChunk> selected, long startNanos) {
        if (!properties.isLogEnabled()) return;
        try {
            var passages = selected.stream().map(chunk -> new Passage(chunk.chunkId(), chunk.documentId(),
                    chunk.pageStart(), chunk.pageEnd(), chunk.content().length())).toList();
            AUDIT.info(mapper.writeValueAsString(new Event(taskId, section, batch, phase, outcome,
                    candidateCount, passages, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos))));
        } catch (RuntimeException ex) {
            log.warn("文档任务检索日志写入失败 taskId={}", taskId);
        }
    }

    private record Event(String taskId, int section, int batch, String phase, String outcome,
                         int candidateCount, List<Passage> selected, long elapsedMillis) {}
    private record Passage(String chunkId, String documentId, Integer pageStart, Integer pageEnd, int characters) {}
}
