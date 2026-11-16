package com.wuyunbin.rag.service.generation.model;

/** Read-time comparison with the archived generation snapshot. */
public record CurrentSource(Boolean originalAvailable, ChunkStatus chunkStatus) {
    public enum ChunkStatus { UNCHANGED, CHANGED, MISSING, UNKNOWN }
}
