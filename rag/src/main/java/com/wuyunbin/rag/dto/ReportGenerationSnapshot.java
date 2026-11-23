package com.wuyunbin.rag.dto;

public record ReportGenerationSnapshot(String model, String modelOrigin, Double temperature,
                                       Double topP, Integer maxTokens, String outputDirectory) {}
