package com.wuyunbin.rag.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wuyunbin.rag.cleaning.ChunkingProperties;
import com.wuyunbin.rag.cleaning.CleaningTextSplitter;
import com.wuyunbin.rag.dto.DocumentMetadata;
import com.wuyunbin.rag.dto.DocumentResponse;
import com.wuyunbin.rag.dto.DocumentStatus;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkEntity;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
import com.wuyunbin.rag.service.storage.DocumentStorage;
import com.wuyunbin.rag.service.storage.DocumentStorage.StoredDocument;
import com.wuyunbin.rag.service.storage.LocalDocumentStorage;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.QueryResults;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档导入服务：读取 TXT、Word、PDF、Excel 内容，按 MD5 + 策略版本查重后语义切片写入 Milvus 向量库。
 *
 * <p>导入决策三分支：同 MD5 同版本跳过；同 MD5 不同版本先写新版本再清理旧版本；
 * 全新文档直接导入。</p>
 */
@Slf4j
@Service
public class DocumentService {
    /**
     * Milvus query 单次最大返回数（满足文档列表与切片分页场景）。
     */
    private static final long QUERY_LIMIT_MAX = 16384L;
    private final CleaningTextSplitter cleaningTextSplitter;
    private final VectorStore vectorStore;
    private final MilvusServiceClient milvusClient;
    private final ChunkingProperties chunkingProperties;
    private final WordDocumentParser wordDocumentParser;
    private final PdfDocumentParser pdfDocumentParser;
    private final ExcelDocumentParser excelDocumentParser;
    private final DocumentStorage documentStorage;
    private final DocumentChunkJpaRepository chunkRepository;
    private final ChunkTokenizer chunkTokenizer;
    @Value("${spring.ai.vectorstore.milvus.collectionName:rag_docs}")
    private String collectionName;
    @Value("${spring.ai.vectorstore.milvus.databaseName:default}")
    private String databaseName;

    @Autowired
    public DocumentService(CleaningTextSplitter cleaningTextSplitter, VectorStore vectorStore, MilvusServiceClient milvusClient, ChunkingProperties chunkingProperties, WordDocumentParser wordDocumentParser, PdfDocumentParser pdfDocumentParser, ExcelDocumentParser excelDocumentParser, DocumentStorage documentStorage, DocumentChunkJpaRepository chunkRepository, ChunkTokenizer chunkTokenizer) {
        this.cleaningTextSplitter = cleaningTextSplitter;
        this.vectorStore = vectorStore;
        this.milvusClient = milvusClient;
        this.chunkingProperties = chunkingProperties;
        this.wordDocumentParser = wordDocumentParser;
        this.pdfDocumentParser = pdfDocumentParser;
        this.excelDocumentParser = excelDocumentParser;
        this.documentStorage = documentStorage;
        this.chunkRepository = chunkRepository;
        this.chunkTokenizer = chunkTokenizer;
    }

    public DocumentService(CleaningTextSplitter cleaningTextSplitter, VectorStore vectorStore, MilvusServiceClient milvusClient, ChunkingProperties chunkingProperties, WordDocumentParser wordDocumentParser, PdfDocumentParser pdfDocumentParser, ExcelDocumentParser excelDocumentParser, DocumentChunkJpaRepository chunkRepository, ChunkTokenizer chunkTokenizer) {
        this(cleaningTextSplitter, vectorStore, milvusClient, chunkingProperties, wordDocumentParser, pdfDocumentParser, excelDocumentParser, new LocalDocumentStorage("data/documents"), chunkRepository, chunkTokenizer);
    }

    /**
     * 从文件名推导类型。
     */
    private static String fileType(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".txt")) return "TXT";
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".xlsx")) return "XLSX";
        if (lower.endsWith(".xls")) return "XLS";
        return null;
    }

    private static String str(Object o) {
        return o != null ? String.valueOf(o) : null;
    }

    static Map<String, Object> metadataMap(Object metadata) {
        if (metadata instanceof Map<?, ?> map) {
            Map<String, Object> result = new HashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        if (metadata instanceof JsonObject json) {
            return new Gson().fromJson(json, Map.class);
        }
        return Map.of();
    }

    private static Long longOrNull(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o != null) {
            try {
                return Long.parseLong(o.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Integer intOrNull(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try {
                return Integer.parseInt(o.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static LocalDateTime parseDateTime(Object o) {
        if (o == null) return null;
        String s = o.toString();
        if (s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            // 兼容老格式（不带 T）
            try {
                return LocalDateTime.parse(s.replace(' ', 'T'));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /**
     * 导入 .txt 文件：按 MD5 + 策略版本决策，重复跳过，版本变化重建，新文档直接导入。
     */
    @Transactional
    public DocumentResponse importTxt(MultipartFile file) {
        validateTxt(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取文件内容失败: " + file.getOriginalFilename(), e);
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        return importContent(bytes, file.getOriginalFilename(), content);
    }

    /**
     * 导入 .docx Word 文件：标题样式转为 ATX Markdown 标题后走同一切片管线。
     * MD5 按原始 .docx 字节计算，同一文件重复上传会跳过。
     */
    @Transactional
    public DocumentResponse importWord(MultipartFile file) {
        validateWord(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取文件内容失败: " + file.getOriginalFilename(), e);
        }
        String content;
        try {
            content = wordDocumentParser.parseToMarkdown(new java.io.ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IllegalStateException("Word 文档解析失败: " + file.getOriginalFilename(), e);
        }
        return importContent(bytes, file.getOriginalFilename(), content);
    }

    // ============================================================
    // Phase 1：文档管理 API（list / detail / chunks / delete / reindex）
    // ============================================================

    /**
     * 导入 .pdf 文件：按字号统计检测标题转为 ATX Markdown 后走同一切片管线。
     * MD5 按原始 PDF 字节计算，同一文件重复上传会跳过。
     */
    @Transactional
    public DocumentResponse importPdf(MultipartFile file) {
        validatePdf(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取文件内容失败: " + file.getOriginalFilename(), e);
        }
        String content;
        try {
            content = pdfDocumentParser.parseToMarkdown(new java.io.ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IllegalStateException("PDF 文档解析失败: " + file.getOriginalFilename(), e);
        }
        return importContent(bytes, file.getOriginalFilename(), content);
    }

    /**
     * 导入 Excel（.xlsx/.xls）文件：每个 Sheet 转为 Markdown 表格 + 自然语言描述后走同一切片管线。
     * MD5 按原始 Excel 字节计算，同一文件重复上传会跳过。
     */
    @Transactional
    public DocumentResponse importExcel(MultipartFile file) {
        validateExcel(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取文件内容失败: " + file.getOriginalFilename(), e);
        }
        String content;
        try {
            content = excelDocumentParser.parseToMarkdown(new java.io.ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IllegalStateException("Excel 文档解析失败: " + file.getOriginalFilename(), e);
        }
        return importContent(bytes, file.getOriginalFilename(), content);
    }

    /**
     * 共享导入逻辑：MD5 查重 → 切片 → 写入 Milvus → 旧版本清理。
     */
    private DocumentResponse importContent(byte[] rawBytes, String fileName, String content) {
        String md5 = DigestUtils.md5DigestAsHex(rawBytes);
        try {
            documentStorage.save(md5, fileName, rawBytes);
        } catch (IOException e) {
            throw new IllegalStateException("保存原始文档失败: " + fileName, e);
        }
        String version = chunkingProperties.getPipelineVersion();

        ImportDecision decision = resolveImportDecision(md5, version);
        if (decision == ImportDecision.SKIP_CURRENT) {
            log.warn("文件 [{}] MD5={} 策略版本 {} 已存在，跳过重复导入", fileName, md5, version);
            return new DocumentResponse(0, md5, true, "文件已存在，跳过重复导入");
        }

        if (content.isBlank()) {
            throw new IllegalArgumentException("文件内容为空: " + fileName);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("file_name", fileName);
        metadata.put("doc_md5", md5);
        // Phase 1：补 file_size 与 created_at 到切片元数据，供文档列表/详情展示
        metadata.put("file_size", (long) rawBytes.length);
        metadata.put("created_at", LocalDateTime.now().toString());
        Document document = new Document(content, metadata);
        List<Document> chunks = cleaningTextSplitter.apply(List.of(document));
        vectorStore.add(chunks);
        // Phase 4：切片同步落库 PostgreSQL，供 FTS 关键词检索
        persistChunks(chunks, md5, fileName, version);

        String message = "导入成功";
        if (decision == ImportDecision.REBUILD_NEW_VERSION) {
            message = "导入成功，已按策略版本重建切片";
            deleteOldVersions(md5, version);
        }

        log.info("导入文档 [{}] MD5={} 决策={}，清洗后切片 {} 块", fileName, md5, decision, chunks.size());
        return new DocumentResponse(chunks.size(), md5, false, message);
    }

    /**
     * 导入决策：MD5+版本同时命中跳过；仅 MD5 命中说明版本变化需重建；否则按新文档导入。
     */
    public ImportDecision resolveImportDecision(String md5, String version) {
        long sameVersion = countByExpr("metadata[\"doc_md5\"] == \"" + md5
                + "\" and metadata[\"chunk_pipeline_version\"] == \"" + version + "\"");
        if (sameVersion > 0) {
            return ImportDecision.SKIP_CURRENT;
        }
        long anyVersion = countByExpr("metadata[\"doc_md5\"] == \"" + md5 + "\"");
        return anyVersion > 0 ? ImportDecision.REBUILD_NEW_VERSION : ImportDecision.NEW_DOCUMENT;
    }

    /**
     * 存在性计数查询：命中任意一条即视为存在。
     */
    long countByExpr(String expr) {
        QueryParam param = QueryParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(collectionName)
                .withExpr(expr)
                .addOutField("doc_id")
                .withLimit(1L)
                .build();
        R<QueryResults> response = milvusClient.query(param);
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("切片存在性查询失败: " + response.getMessage());
        }
        return new QueryResultsWrapper(response.getData()).getRowCount();
    }

    /**
     * 清理同一文档旧策略版本的切片（Milvus + PostgreSQL）。
     * 失败仅记录日志，不影响已成功写入的新版本。
     */
    private void deleteOldVersions(String md5, String currentVersion) {
        String expr = "metadata[\"doc_md5\"] == \"" + md5
                + "\" and metadata[\"chunk_pipeline_version\"] != \"" + currentVersion + "\"";
        try {
            R<MutationResult> response = milvusClient.delete(DeleteParam.newBuilder()
                    .withDatabaseName(databaseName)
                    .withCollectionName(collectionName)
                    .withExpr(expr)
                    .build());
            if (response.getStatus() != R.Status.Success.getCode()) {
                log.error("清理旧版本切片失败 md5={} currentVersion={} message={}",
                        md5, currentVersion, response.getMessage());
            } else {
                log.info("已清理旧版本切片 md5={} currentVersion={}", md5, currentVersion);
            }
        } catch (Exception e) {
            log.error("清理旧版本切片异常 md5={} currentVersion={}", md5, currentVersion, e);
        }
        // 同步清理 PostgreSQL 旧版本切片
        try {
            chunkRepository.deleteOldVersions(md5, currentVersion);
        } catch (Exception e) {
            log.error("清理 PG 旧版本切片异常 md5={} currentVersion={}", md5, currentVersion, e);
        }
    }

    /**
     * 将切片落库 PostgreSQL，供 FTS 关键词检索。
     * 与 Milvus 写入对齐：chunk_id / doc_md5 / chunk_index 等元数据一致。
     */
    private void persistChunks(List<Document> chunks, String docMd5, String fileName, String version) {
        try {
            List<DocumentChunkEntity> entities = chunks.stream().map(chunk -> {
                Map<String, Object> md = chunk.getMetadata();
                DocumentChunkEntity e = new DocumentChunkEntity();
                e.setChunkId(str(md.get("chunk_id")));
                e.setDocMd5(docMd5);
                e.setFileName(fileName);
                e.setContent(chunk.getText() == null ? "" : chunk.getText());
                e.setTokens(chunkTokenizer.tokenize(chunk.getText()));
                e.setChunkIndex(intOrNull(md.get("chunk_index")) != null ? intOrNull(md.get("chunk_index")) : 0);
                e.setPipelineVersion(version);
                e.setBlockType(str(md.get("block_type")));
                e.setSectionPath(str(md.get("section_path")));
                e.setPageStart(intOrNull(md.get("page_start")));
                e.setPageEnd(intOrNull(md.get("page_end")));
                e.setCreatedAt(LocalDateTime.now());
                return e;
            }).toList();
            chunkRepository.saveAll(entities);
        } catch (Exception e) {
            // 切片落库失败不影响 Milvus 向量检索，但关键词检索会缺失该文档
            log.error("切片落库 PostgreSQL 失败 docMd5={} fileName={}", docMd5, fileName, e);
        }
    }

    /**
     * 文档列表：聚合 Milvus 切片元数据，按 doc_md5 分组。
     * 支持按文件名关键词与文件类型过滤。
     */
    public List<DocumentMetadata> listDocuments(String keyword, String fileType) {
        List<Map<String, Object>> rows = queryAllChunks();
        Map<String, DocumentMetadata> byDoc = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String md5 = str(row.get("doc_md5"));
            if (md5 == null) {
                continue;
            }
            String fileName = str(row.get("file_name"));
            String ft = fileType(fileName);
            if (fileType != null && !fileType.isBlank() && !fileType.equalsIgnoreCase(ft)) {
                continue;
            }
            if (keyword != null && !keyword.isBlank()
                    && (fileName == null || !fileName.toLowerCase().contains(keyword.toLowerCase()))) {
                continue;
            }
            byDoc.compute(md5, (k, existing) -> mergeMeta(existing, row, fileName, ft));
        }
        return new ArrayList<>(byDoc.values());
    }

    /**
     * 文档详情：按 doc_md5 聚合得到元数据；找不到返回 null。
     */
    public DocumentMetadata getDocument(String documentId) {
        List<Map<String, Object>> rows = queryByMd5(documentId, 1);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> first = rows.get(0);
        String fileName = str(first.get("file_name"));
        long count = countByExpr("metadata[\"doc_md5\"] == \"" + documentId + "\"");
        return new DocumentMetadata(
                documentId,
                fileName,
                fileType(fileName),
                documentId,
                longOrNull(first.get("file_size")),
                (int) count,
                str(first.get("chunk_pipeline_version")),
                DocumentStatus.READY.name(),
                parseDateTime(first.get("created_at"))
        );
    }

    /**
     * 文档切片查询：按 doc_md5 查询，返回切片元数据 + content 字段列表（chunk_index 升序）。
     * 支持按 section 关键词过滤。
     */
    public List<Map<String, Object>> listChunks(String documentId, String sectionKeyword) {
        List<Map<String, Object>> rows = queryByMd5WithContent(documentId, QUERY_LIMIT_MAX);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (sectionKeyword != null && !sectionKeyword.isBlank()) {
                String sp = str(row.get("section_path"));
                if (sp == null || !sp.toLowerCase().contains(sectionKeyword.toLowerCase())) {
                    continue;
                }
            }
            result.add(row);
        }
        result.sort((a, b) -> {
            Integer ia = intOrNull(a.get("chunk_index"));
            Integer ib = intOrNull(b.get("chunk_index"));
            if (ia == null) ia = 0;
            if (ib == null) ib = 0;
            return ia.compareTo(ib);
        });
        return result;
    }

    /**
     * 删除文档：按 doc_md5 删除所有切片（含所有版本）。
     * 删除失败抛 IllegalStateException，避免产生"幽灵文档"。
     */
    @Transactional
    public void deleteDocument(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        // 先校验存在
        long exist = countByExpr("metadata[\"doc_md5\"] == \"" + documentId + "\"");
        if (exist == 0) {
            throw new IllegalStateException("文档不存在: " + documentId);
        }
        String expr = "metadata[\"doc_md5\"] == \"" + documentId + "\"";
        R<MutationResult> response = milvusClient.delete(DeleteParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build());
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("删除文档失败: " + response.getMessage());
        }
        // 同步删除 PostgreSQL 切片
        try {
            chunkRepository.deleteByDocMd5(documentId);
        } catch (Exception e) {
            log.error("删除 PG 切片失败 documentId={}", documentId, e);
        }
        log.info("已删除文档及其全部切片 documentId={}", documentId);
    }

    /**
     * 重新索引：先删除该 doc_md5 的所有切片，再按文件类型走对应导入流程。
     * 要求重新上传原始文件（项目未持久化原始文件）。
     */
    @Transactional
    public DocumentResponse reindex(String documentId, MultipartFile file) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        byte[] bytes;
        String fileName;
        try {
            if (file != null && !file.isEmpty()) {
                bytes = file.getBytes();
                fileName = file.getOriginalFilename();
            } else {
                StoredDocument stored = documentStorage.load(documentId).orElseThrow(() -> new IllegalStateException("原始文档不存在，请重新上传文件"));
                bytes = stored.content();
                fileName = stored.fileName();
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取原始文档失败", e);
        }
        if (fileName == null || fileName.isBlank()) throw new IllegalArgumentException("文件名为空");
        String actualMd5 = DigestUtils.md5DigestAsHex(bytes);
        if (!documentId.equals(actualMd5)) {
            throw new IllegalArgumentException(
                    "文件 MD5 与 documentId 不一致：期望=" + documentId + "，实际=" + actualMd5);
        }
        try {
            documentStorage.save(documentId, fileName, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("保存原始文档失败", e);
        }
        // 先删除该文档所有切片（含所有版本）
        deleteDocument(documentId);
        // 按扩展名走对应导入流程
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".txt")) {
            return importTxt(file);
        } else if (lower.endsWith(".docx")) {
            return importWord(file);
        } else if (lower.endsWith(".pdf")) {
            return importPdf(file);
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return importExcel(file);
        }
        throw new IllegalArgumentException("不支持的文件类型: " + fileName);
    }

    /**
     * 查询某 doc_md5 的全部切片元数据。
     */
    private List<Map<String, Object>> queryByMd5(String md5, long limit) {
        String expr = "metadata[\"doc_md5\"] == \"" + md5 + "\"";
        return queryByExpr(expr, limit, false);
    }

    /**
     * 查询某 doc_md5 的全部切片元数据 + content 字段（用于 chunks 接口）。
     */
    private List<Map<String, Object>> queryByMd5WithContent(String md5, long limit) {
        String expr = "metadata[\"doc_md5\"] == \"" + md5 + "\"";
        return queryByExpr(expr, limit, true);
    }

    /**
     * 查询全库切片元数据（用于文档列表聚合）。
     * 取一个较大 limit，避免分页；项目体量通常不超过万级切片。
     */
    private List<Map<String, Object>> queryAllChunks() {
        return queryByExpr("metadata[\"doc_md5\"] != \"\"", QUERY_LIMIT_MAX, false);
    }

    /**
     * 执行 Milvus query，返回 metadata 字段的 List<Map>；includeContent 时同时返回 content。
     */
    private List<Map<String, Object>> queryByExpr(String expr, long limit, boolean includeContent) {
        QueryParam.Builder builder = QueryParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(collectionName)
                .withExpr(expr)
                .addOutField("metadata")
                .withLimit(limit);
        if (includeContent) {
            builder.addOutField("content");
        }
        QueryParam param = builder.build();
        R<QueryResults> response = milvusClient.query(param);
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus 查询失败: " + response.getMessage());
        }
        List<QueryResultsWrapper.RowRecord> records =
                new QueryResultsWrapper(response.getData()).getRowRecords();
        List<Map<String, Object>> result = new ArrayList<>(records.size());
        for (QueryResultsWrapper.RowRecord record : records) {
            Map<String, Object> typed = new HashMap<>();
            typed.putAll(metadataMap(record.get("metadata")));
            if (includeContent) {
                Object content = record.get("content");
                if (content != null) {
                    typed.put("content", content.toString());
                }
            }
            result.add(typed);
        }
        return result;
    }

    /**
     * 合并切片元数据为 DocumentMetadata，count 累加。
     */
    private DocumentMetadata mergeMeta(DocumentMetadata existing, Map<String, Object> row,
                                       String fileName, String ft) {
        if (existing == null) {
            return new DocumentMetadata(
                    str(row.get("doc_md5")),
                    fileName,
                    ft,
                    str(row.get("doc_md5")),
                    longOrNull(row.get("file_size")),
                    1,
                    str(row.get("chunk_pipeline_version")),
                    DocumentStatus.READY.name(),
                    parseDateTime(row.get("created_at"))
            );
        }
        return new DocumentMetadata(
                existing.documentId(),
                existing.fileName(),
                existing.fileType(),
                existing.md5(),
                existing.fileSize() != null ? existing.fileSize() : longOrNull(row.get("file_size")),
                existing.chunkCount() + 1,
                existing.version(),
                existing.status(),
                existing.createdAt() != null ? existing.createdAt() : parseDateTime(row.get("created_at"))
        );
    }

    private void validateTxt(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的 .txt 文件");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".txt")) {
            throw new IllegalArgumentException("仅支持 .txt 文件: " + filename);
        }
    }

    private void validateWord(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的 .docx 文件");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".docx")) {
            throw new IllegalArgumentException("仅支持 .docx 文件: " + filename);
        }
    }

    private void validatePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的 .pdf 文件");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("仅支持 .pdf 文件: " + filename);
        }
    }

    private void validateExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的 Excel 文件");
        }
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new IllegalArgumentException("文件名为空");
        }
        String lower = filename.toLowerCase();
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw new IllegalArgumentException("仅支持 .xlsx 或 .xls 文件: " + filename);
        }
    }

    public StoredDocument loadSource(String documentId) {
        try {
            return documentStorage.load(documentId).orElseThrow(() -> new IllegalStateException("原始文档不存在"));
        } catch (IOException e) {
            throw new IllegalStateException("读取原始文档失败", e);
        }
    }

    /**
     * 导入决策：同版本跳过 / 版本变化重建 / 新文档。
     */
    public enum ImportDecision {
        SKIP_CURRENT, REBUILD_NEW_VERSION, NEW_DOCUMENT
    }
}
