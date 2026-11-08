package com.wuyunbin.rag.cleaning;

import com.wuyunbin.rag.splitter.SemanticTextSplitter;
import com.wuyunbin.rag.testutil.FakeEmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CleaningTextSplitterTest {

    private ChunkingProperties chunkingProperties;
    private CleaningTextSplitter splitter;

    @BeforeEach
    void setUp() {
        chunkingProperties = new ChunkingProperties();
        chunkingProperties.setPipelineVersion("test-v1");
        chunkingProperties.setStrategy("test-greedy");

        CleaningProperties cleaningProperties = new CleaningProperties();
        cleaningProperties.setEnabled(true);
        cleaningProperties.setLowercase(false);
        cleaningProperties.setFilterToc(false);

        splitter = new CleaningTextSplitter(cleaningProperties, chunkingProperties,
                new TableOfContentsRemover(), new StructuredSegmenter(), new TextNormalizer(),
                new SemanticTextSplitter(new FakeEmbeddingModel(), chunkingProperties));
    }

    @Test
    void writesStableMetadataForProseAndTableAndCode() {
        String text = """
                这是一段足够长的正文内容，用来验证语义切片后元数据写入是否正确。\
                这一句话补充了更多上下文，确保正文参与切片并保持块长度合理。\
                最后一句再补充一点内容，让正文切片更有代表性。
                
                | 参数 | 说明 |
                | --- | --- |
                | top_k | 召回条数 |
                | threshold | 相似度阈值 |
                
                ```java
                public class Demo {
                    private final String name;
                }
                ```
                
                表格之后又是一段正文内容，继续讨论实现细节与注意事项。\
                这部分同样会经过语义切片并写入稳定的切片元数据。
                """;
        Map<String, Object> meta = new HashMap<>();
        meta.put("file_name", "demo.txt");
        meta.put("doc_md5", "abc123md5");

        List<Document> chunks = splitter.apply(List.of(new Document(text, meta)));

        assertTrue(chunks.size() >= 2, "应产生多个切片，实际 " + chunks.size());

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> metadata = chunk.getMetadata();
            assertEquals("test-v1", metadata.get("chunk_pipeline_version"));
            assertEquals("test-greedy", metadata.get("chunk_strategy"));
            assertEquals(i, ((Number) metadata.get("chunk_index")).intValue());
            assertEquals(chunks.size(), ((Number) metadata.get("chunk_total")).intValue());
            Object chunkId = metadata.get("chunk_id");
            assertTrue(chunkId != null && ((String) chunkId).length() == 64, "chunk_id 应为 64 位哈希");
            assertTrue(ids.add((String) chunkId), "chunk_id 不应重复");
            assertTrue(metadata.containsKey("block_type"));
        }
        assertEquals(chunks.size(), ids.size());

        // 表格与代码块路径也必须携带同样的策略元数据
        assertTrue(chunks.stream().anyMatch(c -> "table".equals(c.getMetadata().get("block_type"))));
        assertTrue(chunks.stream().anyMatch(c -> "code".equals(c.getMetadata().get("block_type"))));
        chunks.stream().filter(c -> !"prose".equals(c.getMetadata().get("block_type")))
                .forEach(c -> {
                    assertEquals("test-v1", c.getMetadata().get("chunk_pipeline_version"));
                    assertEquals("test-greedy", c.getMetadata().get("chunk_strategy"));
                    assertTrue(c.getMetadata().containsKey("chunk_id"));
                });
    }

    @Test
    void writesEmptySectionMetadataForDocumentWithoutHeadings() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("doc_md5", "no-heading-md5");
        String text = "无标题文档的第一段正文。无标题文档的第二段正文，继续补充内容。";

        List<Document> chunks = splitter.apply(List.of(new Document(text, meta)));

        assertFalse(chunks.isEmpty());
        for (Document chunk : chunks) {
            assertEquals("", chunk.getMetadata().get("section_id"));
            assertEquals("", chunk.getMetadata().get("section_path"));
            assertEquals(0, ((Number) chunk.getMetadata().get("section_level")).intValue());
        }
    }

    @Test
    void doesNotMergeAcrossHeadingBoundaries() {
        // 相似度阈值压到 0.0，让旧算法尽可能倾向合并；标题硬边界仍然必须生效
        chunkingProperties.setSimilarityThreshold(0.0);
        String text = """
                # 学校
                学校实行冬季作息。
                
                ## 宿舍
                宿舍晚上十一点熄灯。
                
                ## 食堂
                食堂早上六点开放。
                """;
        Map<String, Object> meta = new HashMap<>();
        meta.put("doc_md5", "heading-md5");

        List<Document> chunks = splitter.apply(List.of(new Document(text, meta)));

        assertTrue(chunks.size() >= 3, "三个章节至少生成三个切片，实际 " + chunks.size());
        for (Document chunk : chunks) {
            String body = chunk.getText();
            // 不存在同时横跨两个章节正文的切片
            assertFalse(body.contains("宿舍晚上十一点熄灯") && body.contains("食堂早上六点开放"),
                    "切片跨越了宿舍/食堂标题边界: " + body);
            String path = (String) chunk.getMetadata().get("section_path");
            assertTrue(path.startsWith("学校"), "章节路径应以学校开头: " + path);
            if (body.contains("宿舍晚上十一点熄灯")) {
                assertEquals("学校 / 宿舍", path);
                assertEquals(2, ((Number) chunk.getMetadata().get("section_level")).intValue());
            }
            if (body.contains("食堂早上六点开放")) {
                assertEquals("学校 / 食堂", path);
            }
        }
    }

    @Test
    void tableAndCodeChunksShareSectionPath() {
        String text = """
                # 收费
                收费说明正文，补充一些上下文内容让切片结构完整。
                
                | 项目 | 金额 |
                | --- | --- |
                | 学费 | 5460 |
                
                ```java
                int fee = 5460;
                ```
                """;
        Map<String, Object> meta = new HashMap<>();
        meta.put("doc_md5", "section-blocks-md5");

        List<Document> chunks = splitter.apply(List.of(new Document(text, meta)));

        List<Document> tables = chunks.stream()
                .filter(c -> "table".equals(c.getMetadata().get("block_type"))).toList();
        List<Document> codes = chunks.stream()
                .filter(c -> "code".equals(c.getMetadata().get("block_type"))).toList();
        assertFalse(tables.isEmpty());
        assertFalse(codes.isEmpty());
        for (Document chunk : tables) {
            assertEquals("收费", chunk.getMetadata().get("section_path"));
            assertEquals(1, ((Number) chunk.getMetadata().get("section_level")).intValue());
        }
        for (Document chunk : codes) {
            assertEquals("收费", chunk.getMetadata().get("section_path"));
        }
    }

    @Test
    void sameInputProducesSameChunkIds() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("doc_md5", "repeat-md5");
        String text = "重复性验证正文。这一段会两次进入切片器，产出的 chunk_id 必须完全一致，保证跨版本可追踪。";

        List<Document> first = splitter.apply(List.of(new Document(text, meta)));
        List<Document> second = splitter.apply(List.of(new Document(text, meta)));

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).getMetadata().get("chunk_id"),
                    second.get(i).getMetadata().get("chunk_id"));
        }
    }
}
