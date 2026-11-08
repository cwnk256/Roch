package com.wuyunbin.rag.testutil;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 测试用假嵌入模型：按文本哈希生成确定性向量，不访问网络。
 * 向量维度 8，同一文本永远得到同一向量。
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 8;

    /**
     * 由文本哈希播种伪随机序列，保证跨 JVM 确定性（String.hashCode 由规范定义）。
     */
    public static float[] deterministicVector(String text) {
        Random random = new Random(text.hashCode() * 31L + text.length());
        float[] vector = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
        }
        return vector;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>(instructions.size());
        for (int i = 0; i < instructions.size(); i++) {
            embeddings.add(new Embedding(deterministicVector(instructions.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        String text = document.getText();
        return deterministicVector(text == null ? "" : text);
    }
}
