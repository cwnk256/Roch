package com.wuyunbin.rag.retrieval;

import org.springframework.ai.document.Document;

import java.util.Map;

/**
 * 检索命中项：一次 similaritySearch 返回的单条切片映射。
 * 是响应 Reference 与日志 hits[] 的单一数据源。
 */
public record RetrievalHit(
        int rank,
        String docId,
        double score,
        String fileName,
        String docMd5,
        Integer chunkIndex,
        Integer chunkTotal,
        int textLength,
        String textPreview
) {

    /**
     * 从 Document 构造命中项（唯一映射入口）。
     *
     * @param rank         排名，从 1 开始
     * @param doc          Spring AI 检索返回的文档
     * @param previewChars 正文预览字符数，0 表示不记录正文
     */
    public static RetrievalHit from(int rank, Document doc, int previewChars) {
        Map<String, Object> md = doc.getMetadata();
        String text = doc.getText() == null ? "" : doc.getText();
        String preview = previewChars > 0
                ? text.substring(0, Math.min(previewChars, text.length()))
                : null;
        Double rawScore = doc.getScore();
        return new RetrievalHit(
                rank,
                doc.getId(),
                rawScore != null ? rawScore : 0.0,
                str(md.get("file_name")),
                str(md.get("doc_md5")),
                intOrNull(md.get("chunk_index")),
                intOrNull(md.get("chunk_total")),
                text.length(),
                preview
        );
    }

    private static String str(Object o) {
        return o != null ? String.valueOf(o) : null;
    }

    private static Integer intOrNull(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(o.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
