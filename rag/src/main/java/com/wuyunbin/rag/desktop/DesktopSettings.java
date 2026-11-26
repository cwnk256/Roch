package com.wuyunbin.rag.desktop;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record DesktopSettings(String model, Double temperature, Double topP, Integer maxTokens,
                              String outputDirectory, String modelOrigin, String embeddingModel, int embeddingDimension) {
    static DesktopSettings defaults(Path directory) {
        return new DesktopSettings("", null, null, null, directory.resolve("reports").toString(),
                "http://127.0.0.1:1234", "text-embedding-bge-m3", 1024);
    }

    DesktopSettings validated() {
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("温度必须介于 0 和 2 之间。");
        }
        if (topP != null && (!Double.isFinite(topP) || topP <= 0 || topP > 1)) {
            throw new IllegalArgumentException("Top P 必须大于 0 且不超过 1。");
        }
        if (maxTokens != null && (maxTokens < 1 || maxTokens > 131072)) {
            throw new IllegalArgumentException("最大输出 token 数超出范围。");
        }
        if (outputDirectory == null || outputDirectory.isBlank()) {
            throw new IllegalArgumentException("请选择报告输出目录。");
        }
        Path output = Path.of(outputDirectory.strip()).normalize();
        if (!output.isAbsolute() || output.toString().startsWith("\\\\")) {
            throw new IllegalArgumentException("请选择本机磁盘上的绝对目录。");
        }
        if (embeddingDimension < 1 || embeddingDimension > 32768) {
            throw new IllegalArgumentException("嵌入向量维度超出范围。");
        }
        return new DesktopSettings(modelId(model, true), temperature, topP, maxTokens, output.toString(),
                normalizeOrigin(modelOrigin), modelId(embeddingModel, false), embeddingDimension);
    }

    static String modelId(String value, boolean optional) {
        String id = value == null ? "" : value.strip();
        if ((!optional && id.isEmpty()) || id.length() > 300 || id.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("请选择有效的本地模型。");
        }
        return id;
    }

    static String normalizeOrigin(String text) {
        try {
            URI uri = URI.create(text.strip());
            String host = uri.getHost();
            String path = uri.getRawPath();
            if ("http".equalsIgnoreCase(uri.getScheme()) && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))
                    && uri.getPort() > 0 && uri.getPort() <= 65535 && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (path.isEmpty() || path.equals("/") || path.equals("/v1") || path.equals("/v1/"))) {
                return "http://127.0.0.1:" + uri.getPort();
            }
        } catch (IllegalArgumentException | NullPointerException ignored) {
        }
        throw new IllegalArgumentException("请输入本机 LM Studio 地址，例如 http://127.0.0.1:1234。");
    }

    public Connection connection() {
        return new Connection(modelOrigin, embeddingModel, embeddingDimension);
    }

    public record Connection(String modelOrigin, String embeddingModel, int embeddingDimension) {
        public String collectionName() {
            String identity = modelOrigin + "\n" + embeddingModel + "\n" + embeddingDimension;
            try {
                return "roch_desktop_" + HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
