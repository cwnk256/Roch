package com.wuyunbin.rag.splitter;

import com.wuyunbin.rag.cleaning.ChunkingProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 语义切片器：基于相邻句子的向量相似度切分文本。
 * 相似度低于阈值处断开成块，同时保证块长度在 [minChunkSize, maxChunkSize] 字符范围内。
 */
public class SemanticTextSplitter implements DocumentTransformer {

    /**
     * 句子边界：中文标点、换行、或英文句号+空格（分隔符保留在前一段末尾）。
     */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？!?；;\n])|(?<=\\. )");

    private final EmbeddingModel embeddingModel;
    private final double similarityThreshold;
    private final int minChunkSize;
    private final int maxChunkSize;
    private final int embedBatchSize;

    /**
     * 切片参数由 {@link ChunkingProperties} 外置配置，默认值与旧版硬编码行为一致。
     * 批大小与最大块长度做下界保护，防止非法配置导致切片循环无法推进。
     */
    public SemanticTextSplitter(EmbeddingModel embeddingModel, ChunkingProperties properties) {
        this.embeddingModel = embeddingModel;
        this.similarityThreshold = properties.getSimilarityThreshold();
        this.minChunkSize = properties.getMinChunkSize();
        this.maxChunkSize = Math.max(1, properties.getMaxChunkSize());
        this.embedBatchSize = Math.max(1, properties.getEmbedBatchSize());
    }

    public int getMinChunkSize() {
        return minChunkSize;
    }

    public int getMaxChunkSize() {
        return maxChunkSize;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        for (Document document : documents) {
            result.addAll(split(document));
        }
        return result;
    }

    private List<Document> split(Document document) {
        List<String> chunks = splitProse(document.getText());
        List<Document> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new HashMap<>(document.getMetadata());
            metadata.put("chunk_index", i);
            metadata.put("chunk_total", chunks.size());
            result.add(new Document(chunks.get(i), metadata));
        }
        return result;
    }

    /**
     * 正文语义切片：分句 → 批量向量化 → 相邻句相似度合并 → 小块合并。
     * 供清洗编排器对 PROSE 段调用。
     */
    public List<String> splitProse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> sentences = splitSentences(text);
        if (sentences.isEmpty()) {
            return List.of();
        }

        List<float[]> vectors = embedAll(sentences);
        return mergeSmallChunks(groupBySimilarity(sentences, vectors));
    }

    /**
     * 结构化原子块切片（表格/代码块）：不做语义拆分，尽量按行边界打包到不超过
     * maxChunkSize；单行超长时硬切。保证结构内容不被句子边界打散。
     */
    public List<String> splitAtomic(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            // 单行超长：先冲刷已有内容，再按 maxChunkSize 硬切
            if (line.length() > maxChunkSize) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                for (int start = 0; start < line.length(); start += maxChunkSize) {
                    chunks.add(line.substring(start, Math.min(start + maxChunkSize, line.length())));
                }
                continue;
            }
            if (current.length() > 0 && current.length() + 1 + line.length() > maxChunkSize) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private List<String> splitSentences(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> sentences = new ArrayList<>();
        for (String sentence : SENTENCE_BOUNDARY.split(normalized)) {
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    private List<float[]> embedAll(List<String> sentences) {
        List<float[]> vectors = new ArrayList<>(sentences.size());
        for (int start = 0; start < sentences.size(); start += embedBatchSize) {
            List<String> batch = sentences.subList(start, Math.min(start + embedBatchSize, sentences.size()));
            vectors.addAll(embeddingModel.embed(batch));
        }
        return vectors;
    }

    /**
     * 贪心合并：与前一句相似度达标且总长不超限时并入当前块，否则开启新块。
     */
    private List<String> groupBySimilarity(List<String> sentences, List<float[]> vectors) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        float[] lastVector = null;
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            float[] vector = vectors.get(i);

            // 单句超长且无标点可断：按 maxChunkSize 硬切兜底
            if (sentence.length() > maxChunkSize) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current = new StringBuilder();
                }
                for (int start = 0; start < sentence.length(); start += maxChunkSize) {
                    chunks.add(sentence.substring(start, Math.min(start + maxChunkSize, sentence.length())));
                }
                lastVector = vector;
                continue;
            }

            boolean similar = lastVector != null
                    && cosineSimilarity(lastVector, vector) >= similarityThreshold;
            boolean fits = current.length() + sentence.length() <= maxChunkSize;
            if (similar && fits) {
                current.append(sentence);
            } else {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                }
                current = new StringBuilder(sentence);
            }
            lastVector = vector;
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /**
     * 过小的块并入后续块，避免碎片化。
     */
    private List<String> mergeSmallChunks(List<String> chunks) {
        List<String> merged = new ArrayList<>();
        for (String chunk : chunks) {
            if (!merged.isEmpty()) {
                String previous = merged.get(merged.size() - 1);
                if (previous.length() < minChunkSize && previous.length() + chunk.length() <= maxChunkSize) {
                    merged.set(merged.size() - 1, previous + chunk);
                    continue;
                }
            }
            merged.add(chunk);
        }
        return merged;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
