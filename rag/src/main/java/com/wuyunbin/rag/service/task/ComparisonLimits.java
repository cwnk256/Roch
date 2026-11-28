package com.wuyunbin.rag.service.task;

import java.util.HashSet;
import java.util.List;

public final class ComparisonLimits {
    private ComparisonLimits() {}

    /** A provisional resource budget, independent from model or retrieval quality thresholds. */
    public static void validate(List<String> documentIds, List<String> dimensions) {
        if (documentIds == null || documentIds.size() < 2 || new HashSet<>(documentIds).size() != documentIds.size()) {
            throw new IllegalArgumentException("文档对比需要至少两份不同的文档，且不得重复选择");
        }
        if (dimensions == null || dimensions.isEmpty() || dimensions.stream().anyMatch(
                value -> value == null || value.isBlank() || value.length() > 120)
                || new HashSet<>(dimensions).size() != dimensions.size()) {
            throw new IllegalArgumentException("比较维度须非空且不重复，每项不超过 120 字");
        }
        if (documentIds.size() > 6 || dimensions.size() > 6 || documentIds.size() * dimensions.size() > 24) {
            throw new IllegalArgumentException("文档对比最多 6 份文档、6 个维度，文档数与维度数乘积不可超过 24");
        }
    }
}
