package com.wuyunbin.rag.dto;

import java.time.LocalDateTime;

public record DocumentArtifactResponse(String artifactId, String fileName, String contentType, long fileSize,
                                       LocalDateTime createdAt, String downloadUrl) {
}
