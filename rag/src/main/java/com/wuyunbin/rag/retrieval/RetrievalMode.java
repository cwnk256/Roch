package com.wuyunbin.rag.retrieval;

/**
 * 检索模式。
 *
 * <ul>
 *   <li>{@link #VECTOR}：纯向量检索（Milvus 语义相似度），默认模式，行为与历史版本完全一致；</li>
 *   <li>{@link #KEYWORD}：纯关键词检索（PostgreSQL FTS + Jieba 分词）；</li>
 *   <li>{@link #HYBRID}：向量 + 关键词双路召回，RRF 融合排序。</li>
 * </ul>
 *
 * <p>Hybrid 默认关闭，需要 benchmark 证据证明收益后再调整默认模式。</p>
 */
public enum RetrievalMode {

    VECTOR,
    KEYWORD,
    HYBRID;

    /**
     * 大小写不敏感解析；null/空白返回 null（表示"未指定，走全局默认"）。
     *
     * @throws IllegalArgumentException 非合法模式名
     */
    public static RetrievalMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "vector" -> VECTOR;
            case "keyword" -> KEYWORD;
            case "hybrid" -> HYBRID;
            default -> throw new IllegalArgumentException(
                    "不支持的检索模式: " + value + "（合法值：vector / keyword / hybrid）");
        };
    }
}
