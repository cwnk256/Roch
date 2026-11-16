package com.wuyunbin.rag.service.generation.model;

import java.util.List;

public record VerifiedSummary(String text, List<String> claimIds, List<Claim> additionalClaims) {
    public VerifiedSummary {
        claimIds = claimIds == null ? List.of() : List.copyOf(claimIds);
        additionalClaims = additionalClaims == null ? List.of() : List.copyOf(additionalClaims);
    }
}
