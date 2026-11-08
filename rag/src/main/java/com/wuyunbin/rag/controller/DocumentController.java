package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.dto.*;
import com.wuyunbin.rag.retrieval.KeywordRetriever;
import com.wuyunbin.rag.service.DocumentAggregationService;
import com.wuyunbin.rag.service.DocumentSearchService;
import com.wuyunbin.rag.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文档管理接口：导入（TXT/Word/PDF/Excel）+ Phase 1 文档检索（列表/详情/切片/搜索/删除/重新索引）。
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Tag(name = "文档管理接口", description = "文档导入与 Phase 1 文档检索能力（列表/详情/切片/搜索/删除/重新索引）")
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentSearchService documentSearchService;
    private final KeywordRetriever keywordRetriever;
    private final DocumentAggregationService aggregationService;

    // ============ 导入接口 ============

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "导入 TXT 文档", description = "上传 .txt 文件，按 MD5 查重后语义切片并向量化写入 Milvus；重复文件跳过导入")
    public ResponseEntity<?> importTxt(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(documentService.importTxt(file));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/word", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "导入 Word 文档",
            description = "上传 .docx 文件，提取标题层级转为 Markdown 后语义切片写入 Milvus；标题样式自动映射为 # ~ ###### 以复用 heading-aware 切片")
    public ResponseEntity<?> importWord(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(documentService.importWord(file));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "导入 PDF 文档",
            description = "上传 .pdf 文件，按字号统计检测标题转为 ATX Markdown 后语义切片写入 Milvus；标题自动映射为 # ~ ### 以复用 heading-aware 切片")
    public ResponseEntity<?> importPdf(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(documentService.importPdf(file));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "导入 Excel 文档",
            description = "上传 .xlsx/.xls 文件，每个 Sheet 转为 Markdown 表格 + 自然语言描述后语义切片写入 Milvus；大表格自动分块")
    public ResponseEntity<?> importExcel(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(documentService.importExcel(file));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ============ Phase 1 文档管理接口 ============

    @GetMapping
    @Operation(summary = "文档列表",
            description = "聚合 Milvus 切片元数据返回文档列表，支持按文件名关键词和文件类型过滤；分页参数 page/size")
    public ResponseEntity<PageResult<DocumentMetadata>> listDocuments(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "fileType", required = false) String fileType) {
        List<DocumentMetadata> all = documentService.listDocuments(keyword, fileType);
        return ResponseEntity.ok(PageResult.of(all, page, size));
    }

    @GetMapping("/{documentId}")
    @Operation(summary = "文档详情", description = "按 documentId（doc_md5）查询文档元数据；不存在返回 404")
    public ResponseEntity<?> getDocument(@PathVariable String documentId) {
        DocumentMetadata meta = documentService.getDocument(documentId);
        if (meta == null) {
            return ResponseEntity.status(404).body(Map.of("error", "文档不存在: " + documentId));
        }
        return ResponseEntity.ok(meta);
    }

    @GetMapping("/{documentId}/chunks")
    @Operation(summary = "文档切片查询",
            description = "按 documentId 查询文档切片，支持 section 关键词过滤与分页；pageStart/pageEnd 当前未持久化返回 null")
    public ResponseEntity<?> listChunks(
            @PathVariable String documentId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "section", required = false) String section) {
        try {
            List<Map<String, Object>> rows = documentService.listChunks(documentId, section);
            List<ChunkView> views = rows.stream().map(ChunkView::from).toList();
            return ResponseEntity.ok(PageResult.of(views, page, size));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/search")
    @Operation(summary = "文档搜索",
            description = "自然语言文档搜索：先在 Milvus 做切片检索，再按 doc_md5 聚合为文档级结果；支持 documentIds 过滤")
    public ResponseEntity<?> search(@RequestBody DocumentSearchRequest request) {
        try {
            DocumentSearchResponse response = documentSearchService.search(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Phase 4 Step 2：关键词检索（PostgreSQL FTS + Jieba 分词）。
     * 与向量检索并列，供 RetrievalPipeline 做 Hybrid + RRF 融合。
     */
    @PostMapping("/search-keywords")
    @Operation(summary = "关键词检索",
            description = "基于 PostgreSQL FTS（Jieba 分词 + simple 配置）的关键词检索，按 ts_rank 降序；支持 documentIds 过滤")
    public ResponseEntity<?> searchKeywords(@RequestBody DocumentSearchRequest request) {
        try {
            RetrievalOptions options = new RetrievalOptions(
                    request.topK() != null ? request.topK() : 10,
                    request.threshold() != null ? request.threshold() : 0.0,
                    request.documentIds()
            );
            List<RetrievedChunk> chunks = keywordRetriever.search(request.query(), options);
            List<RetrievedDocument> documents = aggregationService.aggregate(chunks);
            return ResponseEntity.ok(new DocumentSearchResponse(request.query(), documents));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{documentId}")
    @Operation(summary = "删除文档", description = "按 documentId 删除 Milvus 中所有切片（含所有版本）；文档不存在返回 404")
    public ResponseEntity<?> deleteDocument(@PathVariable String documentId) {
        try {
            documentService.deleteDocument(documentId);
            return ResponseEntity.ok(Map.of("documentId", documentId, "status", "DELETED"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/{documentId}/reindex", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "重新索引文档",
            description = "按 documentId 删除旧切片后重新导入；要求重新上传与该 documentId（MD5）一致的原始文件")
    public ResponseEntity<?> reindex(
            @PathVariable String documentId,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        try {
            DocumentResponse response = documentService.reindex(documentId, file);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{documentId}/file")
    public ResponseEntity<?> downloadSource(@PathVariable String documentId) {
        try {
            return ResponseEntity.ok().body(documentService.loadSource(documentId));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}
