package com.wuyunbin.rag.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wuyunbin.rag.cleaning.ChunkingProperties;
import com.wuyunbin.rag.cleaning.CleaningTextSplitter;
import com.wuyunbin.rag.dto.DocumentMetadata;
import com.wuyunbin.rag.dto.DocumentResponse;
import com.wuyunbin.rag.dto.DocumentStatus;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkEntity;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
import com.wuyunbin.rag.service.storage.DocumentStorage;
import com.wuyunbin.rag.service.storage.DocumentStorage.StoredDocument;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.orm.iterator.QueryIterator;
import io.milvus.param.dml.QueryIteratorParam;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 文档导入服务：读取 TXT、Word、PDF、Excel 内容，按 MD5 + 策略版本查重后语义切片写入 Milvus 向量库。
 *
 * <p>导入决策三分支：同 MD5 同版本跳过；同 MD5 不同版本先写新版本再清理旧版本；
 * 全新文档直接导入。</p>
 */
@Slf4j
@Service
public class DocumentService {
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
    private final VectorCleanupJournal cleanupJournal;
    @Value("${spring.ai.vectorstore.milvus.collectionName:rag_docs}")
    private String collectionName;
    @Value("${spring.ai.vectorstore.milvus.databaseName:default}")
    private String databaseName;

    @Autowired
    public DocumentService(CleaningTextSplitter cleaningTextSplitter, VectorStore vectorStore,
                           MilvusServiceClient milvusClient, ChunkingProperties chunkingProperties,
                           WordDocumentParser wordDocumentParser, PdfDocumentParser pdfDocumentParser,
                           ExcelDocumentParser excelDocumentParser, DocumentStorage documentStorage,
                           DocumentChunkJpaRepository chunkRepository, ChunkTokenizer chunkTokenizer,
                           VectorCleanupJournal cleanupJournal) {
        this.cleanupJournal = cleanupJournal;
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

    /**
     * 从文件名推导类型。
     */
    private static String fileType(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase(Locale.ROOT);
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

    @Transactional
    public DocumentResponse importTxt(MultipartFile file) {
        validateTxt(file);
        return importUpload(file);
    }

    @Transactional
    public DocumentResponse importWord(MultipartFile file) {
        validateWord(file);
        return importUpload(file);
    }

    @Transactional
    public DocumentResponse importPdf(MultipartFile file) {
        validatePdf(file);
        return importUpload(file);
    }

    @Transactional
    public DocumentResponse importExcel(MultipartFile file) {
        validateExcel(file);
        return importUpload(file);
    }

    private StoredDocument readUpload(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            return new StoredDocument(DigestUtils.md5DigestAsHex(bytes), file.getOriginalFilename(), bytes);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件内容失败: " + file.getOriginalFilename(), e);
        }
    }

    private DocumentResponse importUpload(MultipartFile file) {
        return indexSource(readUpload(file), false);
    }

    private DocumentResponse indexSource(StoredDocument source, boolean force) {
        String md5 = source.documentId();
        String fileName = source.fileName();
        String type = fileType(fileName);
        if (type == null) throw new IllegalArgumentException("不支持的文件类型: " + fileName);
        String version = chunkingProperties.getPipelineVersion();
        if (type.equals("PDF")) {
            String pdfVersion = version + "-pdf3";
            // 保持既有 VARCHAR(32) 约束，同时兼容较长的自定义策略版本。
            version = pdfVersion.length() <= 32 ? pdfVersion
                    : DigestUtils.md5DigestAsHex(pdfVersion.getBytes(StandardCharsets.UTF_8));
        }

        chunkRepository.lockDocument(md5);
        ImportDecision decision = resolveImportDecision(md5, version);
        if (force && decision == ImportDecision.NEW_DOCUMENT && documentStorageMissing(md5)) {
            throw new IllegalStateException("文档不存在: " + md5);
        }
        if (!force && decision == ImportDecision.SKIP_CURRENT) {
            saveSource(source);
            return new DocumentResponse(0, md5, true, "文件已存在，跳过重复导入");
        }

        List<Document> chunks = prepareChunks(source, type, version);
        if (chunks.isEmpty()) throw new IllegalArgumentException("文件没有可索引的正文: " + fileName);
        // 修复曾经只写入其中一侧的文档。物理向量 ID 与稳定的引用 chunk_id 分开。
        List<String> previousIds = decision == ImportDecision.NEW_DOCUMENT ? List.of() : vectorIds(md5);
        chunkRepository.deleteByDocMd5(md5);
        persistChunks(chunks, md5, fileName, version);
        saveSource(source);
        replaceVectors(md5, previousIds, chunks);
        return new DocumentResponse(chunks.size(), md5, false,
                force || decision == ImportDecision.REBUILD_NEW_VERSION ? "已重新索引文档" : "导入成功");
    }

    private List<Document> prepareChunks(StoredDocument source, String type, String version) {
        Map<String, Object> metadata = Map.of("doc_md5", source.documentId(), "file_name", source.fileName(),
                "file_size", (long) source.content().length, "created_at", LocalDateTime.now().toString());
        var input = new ByteArrayInputStream(source.content());
        try {
            if (type.equals("PDF")) {
                List<Document> pages = new ArrayList<>();
                for (var page : pdfDocumentParser.parsePages(input)) {
                    Map<String, Object> pageMetadata = new HashMap<>(metadata);
                    pageMetadata.put("page_start", page.number());
                    pageMetadata.put("page_end", page.number());
                    pages.add(new Document(page.content(), pageMetadata));
                }
                return cleaningTextSplitter.splitPages(pages, version);
            }
            String content = switch (type) {
                case "TXT" -> new String(source.content(), StandardCharsets.UTF_8);
                case "DOCX" -> wordDocumentParser.parseToMarkdown(input);
                case "XLS", "XLSX" -> excelDocumentParser.parseToMarkdown(input);
                default -> throw new IllegalArgumentException("不支持的文件类型: " + type);
            };
            if (content.isBlank()) throw new IllegalArgumentException("文件内容为空: " + source.fileName());
            return cleaningTextSplitter.apply(List.of(new Document(content, metadata)));
        } catch (IOException e) {
            throw new IllegalStateException(type + " 文档解析失败: " + source.fileName(), e);
        }
    }

    private void saveSource(StoredDocument source) {
        try {
            documentStorage.save(source.documentId(), source.fileName(), source.content());
        } catch (IOException e) {
            throw new IllegalStateException("保存原始文档失败: " + source.fileName(), e);
        }
    }

    List<String> vectorIds(String md5) {
        return scanRows(documentFilter(md5), List.of("doc_id")).stream()
                .map(row -> row.get("doc_id").toString()).toList();
    }

    private List<QueryResultsWrapper.RowRecord> scanRows(String filter, List<String> fields) {
        var parameters = QueryIteratorParam.newBuilder()
                .withDatabaseName(databaseName).withCollectionName(collectionName)
                .withExpr(filter).withOutFields(fields)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG).withBatchSize(1000L).build();
        R<QueryIterator> response = milvusClient.queryIterator(parameters);
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("读取向量记录失败: " + response.getMessage());
        }
        QueryIterator iterator = response.getData();
        List<QueryResultsWrapper.RowRecord> rows = new ArrayList<>();
        try {
            for (var batch = iterator.next(); !batch.isEmpty(); batch = iterator.next()) {
                rows.addAll(batch);
            }
            return rows;
        } finally {
            iterator.close();
        }
    }

    private void replaceVectors(String documentId, List<String> previousIds, List<Document> chunks) {
        List<String> newIds = chunks.stream().map(Document::getId).toList();
        UUID batchId = cleanupJournal.prepare(documentId, newIds, previousIds);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                tryCleanObsoleteVectors(batchId);
            }

            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) tryCleanObsoleteVectors(batchId);
            }
        });
        vectorStore.add(chunks);
    }

    private void tryCleanObsoleteVectors(UUID batchId) {
        try {
            cleanupJournal.clean(batchId);
        } catch (RuntimeException exception) {
            log.warn("向量清理失败，保留批次供后台重试 batchId={}", batchId, exception);
        }
    }

    private static String documentFilter(String md5) {
        return "metadata[\"doc_md5\"] == " + new JsonPrimitive(md5);
    }

    /**
     * 导入决策：MD5+版本同时命中跳过；仅 MD5 命中说明版本变化需重建；否则按新文档导入。
     */
    public ImportDecision resolveImportDecision(String md5, String version) {
        String filter = documentFilter(md5);
        if (hasChunks(filter + " and metadata[\"chunk_pipeline_version\"] == " + new JsonPrimitive(version))) {
            return ImportDecision.SKIP_CURRENT;
        }
        return hasChunks(filter) ? ImportDecision.REBUILD_NEW_VERSION : ImportDecision.NEW_DOCUMENT;
    }

    boolean hasChunks(String filter) {
        var query = QueryParam.newBuilder().withDatabaseName(databaseName).withCollectionName(collectionName)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withExpr(filter).addOutField("doc_id").withLimit(1L).build();
        return executeQuery(query).getRowCount() > 0;
    }

    private int countChunks(String documentId) {
        var query = QueryParam.newBuilder().withDatabaseName(databaseName).withCollectionName(collectionName)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withExpr(documentFilter(documentId)).addOutField("count(*)").build();
        Number count = (Number) executeQuery(query).getFieldWrapper("count(*)").getFieldData().getFirst();
        return Math.toIntExact(count.longValue());
    }

    private QueryResultsWrapper executeQuery(QueryParam query) {
        R<QueryResults> response = milvusClient.query(query);
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus 查询失败: " + response.getMessage());
        }
        return new QueryResultsWrapper(response.getData());
    }

    /**
     * 将切片落库 PostgreSQL，供 FTS 关键词检索。
     * 与 Milvus 写入对齐：chunk_id / doc_md5 / chunk_index 等元数据一致。
     */
    private void persistChunks(List<Document> chunks, String docMd5, String fileName, String version) {
        List<DocumentChunkEntity> entities = chunks.stream().map(chunk -> {
            Map<String, Object> md = chunk.getMetadata();
            DocumentChunkEntity entity = new DocumentChunkEntity();
            entity.setChunkId(str(md.get("chunk_id")));
            entity.setDocMd5(docMd5);
            entity.setFileName(fileName);
            entity.setContent(chunk.getText() == null ? "" : chunk.getText());
            entity.setTokens(chunkTokenizer.tokenize(chunk.getText()));
            entity.setChunkIndex(intOrNull(md.get("chunk_index")) != null ? intOrNull(md.get("chunk_index")) : 0);
            entity.setPipelineVersion(version);
            entity.setBlockType(str(md.get("block_type")));
            entity.setSectionPath(str(md.get("section_path")));
            entity.setPageStart(intOrNull(md.get("page_start")));
            entity.setPageEnd(intOrNull(md.get("page_end")));
            entity.setCreatedAt(LocalDateTime.now());
            return entity;
        }).toList();
        chunkRepository.saveAllAndFlush(entities);
    }

    /**
     * 文档列表：聚合 Milvus 切片元数据，按 doc_md5 分组。
     * 支持按文件名关键词与文件类型过滤。
     */
    public List<DocumentMetadata> listDocuments(String keyword, String fileType) {
        List<Map<String, Object>> rows = metadataRows(scanRows("metadata[\"doc_md5\"] != \"\"", List.of("metadata")), false);
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
        var firstChunk = QueryParam.newBuilder().withDatabaseName(databaseName).withCollectionName(collectionName)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withExpr(documentFilter(documentId)).addOutField("metadata").withLimit(1L).build();
        List<Map<String, Object>> rows = metadataRows(executeQuery(firstChunk).getRowRecords(), false);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> first = rows.get(0);
        String fileName = str(first.get("file_name"));
        int count = countChunks(documentId);
        return new DocumentMetadata(
                documentId,
                fileName,
                fileType(fileName),
                documentId,
                longOrNull(first.get("file_size")),
                count,
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
        List<Map<String, Object>> rows = metadataRows(scanRows(documentFilter(documentId), List.of("metadata", "content")), true);
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
        chunkRepository.lockDocument(documentId);
        if (!hasChunks(documentFilter(documentId))) {
            throw new IllegalStateException("文档不存在: " + documentId);
        }
        String expr = documentFilter(documentId);
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

    /** 用重新上传的文件或保存的原文强制重建，包括策略版本未变化的情况。 */
    @Transactional
    public DocumentResponse reindex(String documentId, MultipartFile file) {
        if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("documentId 不能为空");
        StoredDocument source = file == null || file.isEmpty() ? loadSource(documentId) : readUpload(file);
        if (!documentId.equals(DigestUtils.md5DigestAsHex(source.content()))) {
            throw new IllegalArgumentException("文件 MD5 与 documentId 不一致");
        }
        return indexSource(source, true);
    }

    private boolean documentStorageMissing(String documentId) {
        try {
            return documentStorage.load(documentId).isEmpty();
        } catch (IOException e) {
            throw new IllegalStateException("读取原始文档失败: " + documentId, e);
        }
    }

    private List<Map<String, Object>> metadataRows(List<QueryResultsWrapper.RowRecord> records, boolean includeContent) {
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
