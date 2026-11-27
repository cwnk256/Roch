package com.wuyunbin.rag.service.ocr;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wuyunbin.rag.dto.DocumentResponse;

import java.util.UUID;

public record OcrImportJob(UUID jobId, String documentId, Status status, int currentPage, int totalPages,
                           Integer failedPage, String message,
                           @JsonInclude(JsonInclude.Include.NON_NULL) DocumentResponse result) {
    public enum Status {
        QUEUED, RUNNING, INDEXING, SUCCEEDED, FAILED
    }
}
