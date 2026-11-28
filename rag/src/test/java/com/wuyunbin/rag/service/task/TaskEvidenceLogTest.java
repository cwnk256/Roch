package com.wuyunbin.rag.service.task;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wuyunbin.rag.retrieval.RetrievalLogProperties;
import com.wuyunbin.rag.service.generation.ClaimVerifier.AnchorSpan;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskEvidenceLogTest {
    @Test
    void writesScopedAnchorAttemptsInOrderAndOmitsQuestionForLegacySpans() {
        var logger = (Logger) LoggerFactory.getLogger("rag.retrieval");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var properties = new RetrievalLogProperties();
        properties.setLogEnabled(true);
        var mapper = JsonMapper.builder().build();
        var evidenceLog = new TaskEvidenceLog(mapper, properties);
        var first = new AnchorSpan("chunk-a", "doc", 2, 2, 0, 12, 1);
        var supplement = new AnchorSpan("chunk-b", "doc", 3, 3, 5, 18, 1);
        var second = new AnchorSpan("chunk-a", "doc", 2, 2, 0, 12, 2);
        long start = System.nanoTime();
        try {
            evidenceLog.anchors("test-task", 1, 1, List.of(first, supplement, second), start);
            evidenceLog.anchors("test-task", 1, 1, List.of(first, second), start);
            evidenceLog.anchors("legacy-task", 1, 1, List.of(new AnchorSpan("chunk-a", "doc", 2, 2, 0, 12)), start);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        var records = appender.list.stream().map(event -> mapper.readTree(event.getFormattedMessage())).toList();
        assertEquals(3, records.size());
        assertEquals("anchors", records.getFirst().get("phase").asString());
        assertEquals("chunk-b", records.getFirst().get("selected").get(1).get("chunkId").asString());
        var retry = records.get(1).get("selected");
        assertEquals(2, retry.size());
        assertEquals(1, retry.get(0).get("question").intValue());
        assertEquals(2, retry.get(1).get("question").intValue());
        assertFalse(records.getLast().get("selected").get(0).has("question"));
    }
}
