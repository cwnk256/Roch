package com.wuyunbin.rag.service;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 中文分词服务：基于 Jieba 将文本切分为空格分隔的 token 串。
 *
 * <p>输出供 PostgreSQL {@code to_tsvector('simple', tokens)} 建索引检索。
 * simple 配置按空白分词，因此 Java 侧必须把中文切分为以空格分隔的 token。</p>
 */
@Component
public class ChunkTokenizer {

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    /**
     * 将文本分词为空格分隔的 token 串（用于存储 tokens 列）。
     * 空文本返回空串；纯标点/符号可能产出空结果。
     */
    public String tokenize(String text) {
        return tokenWords(text).stream().collect(Collectors.joining(" "));
    }

    /**
     * 将文本分词为 OR 连接的 tsquery 串（如 "校长 | 是 | 谁"），用于 to_tsquery 检索。
     * 使用 OR 语义保证召回率：任一 token 命中即匹配。
     */
    public String tokenizeForQuery(String text) {
        return tokenWords(text).stream().collect(Collectors.joining(" | "));
    }

    private List<String> tokenWords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<SegToken> tokens = segmenter.process(text, JiebaSegmenter.SegMode.SEARCH);
        return tokens.stream()
                .map(t -> t.word)
                .filter(w -> w != null && !w.isBlank())
                .toList();
    }
}
