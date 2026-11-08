package com.wuyunbin.rag.cleaning;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 语义切片配置项：策略版本、策略名与切片参数。
 * 默认值与旧版硬编码行为一致（0.7 / 50 / 1000 / 批 32）。
 */
@Getter
@Setter
@Component
public class ChunkingProperties {

    /**
     * 切片流水线版本：同文件版本变化时触发重建，同版本重复导入跳过。
     */
    @Value("${rag.chunking.pipeline-version:legacy-v1}")
    private String pipelineVersion = "legacy-v1";

    /**
     * 切片策略名，随元数据写入每个切片。
     */
    @Value("${rag.chunking.strategy:legacy-greedy}")
    private String strategy = "legacy-greedy";

    /**
     * 相邻句相似度阈值，低于该值断开成块。
     */
    @Value("${rag.chunking.similarity-threshold:0.7}")
    private double similarityThreshold = 0.7;

    /**
     * 最小块字符数，过小块会向后续块合并。
     */
    @Value("${rag.chunking.min-chunk-size:50}")
    private int minChunkSize = 50;

    /**
     * 最大块字符数，超限断开。
     */
    @Value("${rag.chunking.max-chunk-size:1000}")
    private int maxChunkSize = 1000;

    /**
     * 每批送入嵌入模型的句子数，避免超大文档一次性请求超时。
     */
    @Value("${rag.chunking.embed-batch-size:32}")
    private int embedBatchSize = 32;
}
