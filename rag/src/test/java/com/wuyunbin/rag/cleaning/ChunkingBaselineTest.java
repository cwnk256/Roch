package com.wuyunbin.rag.cleaning;

import com.wuyunbin.rag.splitter.SemanticTextSplitter;
import com.wuyunbin.rag.testutil.FakeEmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 离线切片基线：在固定语料上断言切片不变量，并输出可跨版本比较的长度分布指标。
 * 不访问 LM Studio、Milvus 或网络。
 */
class ChunkingBaselineTest {

    private ChunkingProperties chunkingProperties;
    private CleaningTextSplitter splitter;

    @BeforeEach
    void setUp() {
        chunkingProperties = new ChunkingProperties();
        chunkingProperties.setPipelineVersion("baseline-v1");
        chunkingProperties.setStrategy("legacy-greedy");

        CleaningProperties cleaningProperties = new CleaningProperties();
        cleaningProperties.setEnabled(true);
        cleaningProperties.setLowercase(false);
        cleaningProperties.setFilterToc(true);

        splitter = new CleaningTextSplitter(cleaningProperties, chunkingProperties,
                new TableOfContentsRemover(), new StructuredSegmenter(), new TextNormalizer(),
                new SemanticTextSplitter(new FakeEmbeddingModel(), chunkingProperties));
    }

    private String loadCorpus() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/chunking/baseline-corpus.txt")) {
            assertNotNull(in, "找不到基线语料文件 /chunking/baseline-corpus.txt");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void baselineInvariantsAndMetrics() throws IOException {
        String corpus = loadCorpus();
        assertTrue(corpus.length() >= 3000 && corpus.length() <= 6000,
                "语料长度应在 3000-6000 之间，实际 " + corpus.length());

        Map<String, Object> meta = new HashMap<>();
        meta.put("file_name", "baseline-corpus.txt");
        meta.put("doc_md5", "baseline-doc-md5");
        List<Document> chunks = splitter.apply(List.of(new Document(corpus, meta)));

        assertTrue(chunks.size() > 1, "固定语料应产生多个切片");

        int min = Integer.MAX_VALUE;
        int max = 0;
        long sum = 0;
        long fragments = 0;
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            String text = chunk.getText();
            assertFalse(text == null || text.isBlank(), "切片不得为空");
            assertTrue(text.length() <= chunkingProperties.getMaxChunkSize(),
                    "切片长度不得超过 max-chunk-size，实际 " + text.length());

            Map<String, Object> metadata = chunk.getMetadata();
            assertEquals(i, ((Number) metadata.get("chunk_index")).intValue(), "chunk_index 应连续");
            assertEquals(chunks.size(), ((Number) metadata.get("chunk_total")).intValue(), "chunk_total 应一致");

            String chunkId = (String) metadata.get("chunk_id");
            assertNotNull(chunkId, "chunk_id 不得缺失");
            assertTrue(ids.add(chunkId), "chunk_id 不得重复");
            assertEquals(chunkingProperties.getPipelineVersion(), metadata.get("chunk_pipeline_version"));
            assertEquals(chunkingProperties.getStrategy(), metadata.get("chunk_strategy"));

            int length = text.length();
            min = Math.min(min, length);
            max = Math.max(max, length);
            sum += length;
            if (length < 100) {
                fragments++;
            }
        }

        double avg = (double) sum / chunks.size();
        double fragmentRate = (double) fragments / chunks.size();
        System.out.println("CHUNKING_BASELINE {chunks:" + chunks.size()
                + ",min:" + min
                + ",max:" + max
                + ",avg:" + String.format(Locale.ROOT, "%.1f", avg)
                + ",fragmentRate:" + String.format(Locale.ROOT, "%.2f", fragmentRate)
                + ",strategy:" + chunkingProperties.getStrategy() + "}");
    }
}
