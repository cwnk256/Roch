package com.wuyunbin.rag.service.generation.model;

import java.util.List;

public record Claim(String claimId, String text, List<String> evidenceIds, SupportStatus supportStatus,
                    String reason, boolean automatic) {
    public Claim {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }

    public static Claim unsupported(String id, String text, String reason) {
        return new Claim(id, text, List.of(), SupportStatus.INSUFFICIENT, reason, true);
    }
}
