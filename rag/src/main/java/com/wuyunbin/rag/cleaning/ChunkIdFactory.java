package com.wuyunbin.rag.cleaning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 稳定切片 ID 工厂：由文档 MD5、策略版本、块类型、切片序号与切片正文推导 SHA-256 ID。
 * 同一输入永远得到同一 ID，用于跨版本追踪与去重。
 */
public final class ChunkIdFactory {

    private ChunkIdFactory() {
    }

    /**
     * 生成 64 位小写十六进制 SHA-256 切片 ID。
     */
    public static String create(String docMd5, String pipelineVersion, String blockType,
                                int chunkIndex, String content) {
        String payload = docMd5 + "|" + pipelineVersion + "|" + blockType + "|" + chunkIndex + "|" + content;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
