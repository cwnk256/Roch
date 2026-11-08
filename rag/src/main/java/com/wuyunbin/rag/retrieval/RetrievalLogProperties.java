package com.wuyunbin.rag.retrieval;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 检索日志配置项。
 */
@Getter
@Setter
@Component
public class RetrievalLogProperties {

    @Value("${rag.retrieval.log.enabled:true}")
    private boolean logEnabled;

    @Value("${rag.retrieval.log.preview-chars:0}")
    private int previewChars;

    @Value("${rag.retrieval.buffer-size:500}")
    private int bufferSize;
}
