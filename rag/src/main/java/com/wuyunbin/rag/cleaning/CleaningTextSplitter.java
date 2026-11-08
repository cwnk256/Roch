package com.wuyunbin.rag.cleaning;

import com.wuyunbin.rag.splitter.SemanticTextSplitter;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据清洗 + 切片编排器。
 *
 * <pre>
 * 原文 →（可选）目录过滤 → 结构化分段（PROSE/TABLE/CODE）
 *      →（可选）小写化（仅正文与表格单元格，URL/代码除外）
 *      → PROSE 走语义切片，TABLE/CODE 走原子块切片
 *      → 按原文顺序交错输出，metadata 标记 block_type
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class CleaningTextSplitter implements DocumentTransformer {

    private final CleaningProperties properties;
    private final ChunkingProperties chunkingProperties;
    private final TableOfContentsRemover tableOfContentsRemover;
    private final StructuredSegmenter structuredSegmenter;
    private final TextNormalizer textNormalizer;
    private final SemanticTextSplitter semanticTextSplitter;

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        for (Document document : documents) {
            result.addAll(split(document));
        }
        return result;
    }

    private List<Document> split(Document document) {
        String text = document.getText();
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<ChunkDraft> drafts = new ArrayList<>();
        if (!properties.isEnabled()) {
            // 清洗关闭：等价于旧版纯语义切片，无结构化分段，章节上下文为空
            semanticTextSplitter.splitProse(text)
                    .forEach(chunk -> drafts.add(new ChunkDraft(chunk, "prose", null, SectionContext.EMPTY)));
        } else {
            if (properties.isFilterToc()) {
                text = tableOfContentsRemover.remove(text);
            }
            for (TextSegment segment : structuredSegmenter.segment(text)) {
                switch (segment.type()) {
                    case PROSE -> {
                        String content = properties.isLowercase()
                                ? textNormalizer.normalizeProse(segment.content())
                                : segment.content();
                        semanticTextSplitter.splitProse(content)
                                .forEach(chunk -> drafts.add(
                                        new ChunkDraft(chunk, "prose", null, segment.section())));
                    }
                    case TABLE -> {
                        String content = properties.isLowercase()
                                ? textNormalizer.normalizeTable(segment.content())
                                : segment.content();
                        semanticTextSplitter.splitAtomic(content)
                                .forEach(chunk -> drafts.add(
                                        new ChunkDraft(chunk, "table", null, segment.section())));
                    }
                    case CODE -> semanticTextSplitter.splitAtomic(segment.content())
                            .forEach(chunk -> drafts.add(
                                    new ChunkDraft(chunk, "code", segment.codeLang(), segment.section())));
                }
            }
        }

        List<Document> result = new ArrayList<>(drafts.size());
        String pipelineVersion = chunkingProperties.getPipelineVersion();
        String strategy = chunkingProperties.getStrategy();
        Object md5Value = document.getMetadata().get("doc_md5");
        String docMd5 = md5Value != null ? md5Value.toString() : document.getId();
        for (int i = 0; i < drafts.size(); i++) {
            ChunkDraft draft = drafts.get(i);
            Map<String, Object> metadata = new HashMap<>(document.getMetadata());
            metadata.put("block_type", draft.blockType());
            if (draft.codeLang() != null) {
                metadata.put("code_lang", draft.codeLang());
            }
            metadata.put("chunk_index", i);
            metadata.put("chunk_total", drafts.size());
            metadata.put("chunk_id", ChunkIdFactory.create(docMd5, pipelineVersion, draft.blockType(), i, draft.content()));
            metadata.put("chunk_pipeline_version", pipelineVersion);
            metadata.put("chunk_strategy", strategy);
            metadata.put("section_id", draft.section().sectionId());
            metadata.put("section_path", draft.section().sectionPath());
            metadata.put("section_level", draft.section().sectionLevel());
            result.add(new Document(draft.content(), metadata));
        }
        return result;
    }

    /**
     * 切片草稿：文本 + 块类型 + 可选代码语言 + 所属章节上下文。
     */
    private record ChunkDraft(String content, String blockType, String codeLang, SectionContext section) {
    }
}
