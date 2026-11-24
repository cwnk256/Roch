package com.wuyunbin.rag.dto;

import com.wuyunbin.rag.retrieval.RetrievalMode;
import java.util.Set;

/** 检索参数在任务创建时冻结，历史任务缺少该对象时沿用原逻辑。 */
public record ReportRetrievalSettings(String strategy, int topK, double threshold,
                                      int reportMaxChars, int comparisonMaxChars, RetrievalMode baselineMode) {
    public static final String BASELINE = "baseline";
    private static final String HYBRID = "hybrid-only";
    private static final String SPLIT = "question-split";
    private static final String FULL = "full";

    public ReportRetrievalSettings {
        if (baselineMode == null) baselineMode = RetrievalMode.VECTOR;
        if (strategy == null || !Set.of(BASELINE, HYBRID, SPLIT, FULL).contains(strategy)) {
            throw new IllegalArgumentException("未知的文档任务检索策略：" + strategy);
        }
        if (topK < 1 || !Double.isFinite(threshold) || threshold < 0 || threshold > 1
                || reportMaxChars < 1 || comparisonMaxChars < 1) {
            throw new IllegalArgumentException("文档任务检索参数无效");
        }
    }

    public ReportRetrievalSettings(String strategy, int topK, double threshold,
                                   int reportMaxChars, int comparisonMaxChars) {
        this(strategy, topK, threshold, reportMaxChars, comparisonMaxChars, RetrievalMode.VECTOR);
    }

    public boolean hybrid() {
        return !BASELINE.equals(strategy);
    }

    public boolean splitQuestions() {
        return SPLIT.equals(strategy) || FULL.equals(strategy);
    }

    public boolean strict() {
        return FULL.equals(strategy);
    }
}
