package com.wuyunbin.rag.cleaning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 切片所属章节上下文。
 *
 * @param sectionId    章节路径的 SHA-256 小写十六进制摘要，同一章节的切片共享
 * @param sectionPath  从一级标题到当前标题的完整路径，以 " / " 连接
 * @param sectionLevel 当前标题级别；无标题时为 0
 */
public record SectionContext(String sectionId, String sectionPath, int sectionLevel) {

    /**
     * 文档无标题时使用的空上下文：空 ID、空路径、级别 0。
     */
    public static final SectionContext EMPTY = new SectionContext("", "", 0);

    /**
     * 由当前标题栈生成章节上下文；栈为空时返回 {@link #EMPTY}。
     */
    public static SectionContext from(List<Heading> stack) {
        if (stack == null || stack.isEmpty()) {
            return EMPTY;
        }
        String path = stack.stream()
                .map(Heading::title)
                .collect(Collectors.joining(" / "));
        return new SectionContext(sha256Hex(path), path, stack.get(stack.size() - 1).level());
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
