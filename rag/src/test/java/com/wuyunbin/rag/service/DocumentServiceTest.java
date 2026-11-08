package com.wuyunbin.rag.service;

import com.wuyunbin.rag.cleaning.ChunkingProperties;
import com.wuyunbin.rag.cleaning.CleaningTextSplitter;
import com.wuyunbin.rag.dto.DocumentResponse;
import com.wuyunbin.rag.persistence.chunk.DocumentChunkJpaRepository;
import com.wuyunbin.rag.service.DocumentService.ImportDecision;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class DocumentServiceTest {

    private CleaningTextSplitter cleaningTextSplitter;
    private VectorStore vectorStore;
    private MilvusServiceClient milvusClient;
    private ChunkingProperties chunkingProperties;
    private WordDocumentParser wordDocumentParser;
    private PdfDocumentParser pdfDocumentParser;
    private ExcelDocumentParser excelDocumentParser;
    private DocumentChunkJpaRepository chunkRepository;
    private ChunkTokenizer chunkTokenizer;

    @BeforeEach
    void setUp() {
        cleaningTextSplitter = mock(CleaningTextSplitter.class);
        vectorStore = mock(VectorStore.class);
        milvusClient = mock(MilvusServiceClient.class);
        chunkingProperties = new ChunkingProperties();
        chunkingProperties.setPipelineVersion("legacy-v1");
        wordDocumentParser = mock(WordDocumentParser.class);
        pdfDocumentParser = mock(PdfDocumentParser.class);
        excelDocumentParser = mock(ExcelDocumentParser.class);
        chunkRepository = mock(DocumentChunkJpaRepository.class);
        chunkTokenizer = mock(ChunkTokenizer.class);
    }

    /**
     * 用匿名子类替代 countByExpr 的 Milvus 查询，避免网络依赖，
     * 并区分"同 MD5+版本"与"仅 MD5"两次存在性查询。
     */
    private DocumentService serviceWithCounts(long sameVersionCount, long anyVersionCount) {
        DocumentService service = new DocumentService(cleaningTextSplitter, vectorStore, milvusClient, chunkingProperties, wordDocumentParser, pdfDocumentParser, excelDocumentParser, chunkRepository, chunkTokenizer) {
            @Override
            long countByExpr(String expr) {
                return expr.contains("chunk_pipeline_version") ? sameVersionCount : anyVersionCount;
            }
        };
        ReflectionTestUtils.setField(service, "collectionName", "rag_docs");
        ReflectionTestUtils.setField(service, "databaseName", "default");
        return service;
    }

    private MockMultipartFile txtFile() {
        return new MockMultipartFile("file", "doc.txt", "text/plain",
                "正文内容，足够判定非空。".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void sameMd5SameVersionSkips() {
        DocumentService service = serviceWithCounts(1, 1);
        assertEquals(ImportDecision.SKIP_CURRENT, service.resolveImportDecision("md5", "legacy-v1"));
    }

    @Test
    void sameMd5DifferentVersionRebuilds() {
        DocumentService service = serviceWithCounts(0, 1);
        assertEquals(ImportDecision.REBUILD_NEW_VERSION, service.resolveImportDecision("md5", "legacy-v2"));
    }

    @Test
    void unknownMd5ImportsAsNewDocument() {
        DocumentService service = serviceWithCounts(0, 0);
        assertEquals(ImportDecision.NEW_DOCUMENT, service.resolveImportDecision("md5", "legacy-v1"));
    }

    @Test
    void rebuildWritesNewVersionThenDeletesOld() {
        DocumentService service = serviceWithCounts(0, 1);
        when(cleaningTextSplitter.apply(anyList()))
                .thenReturn(List.of(new Document("块一", Map.of()), new Document("块二", Map.of())));
        when(milvusClient.delete(any(DeleteParam.class))).thenReturn(R.success((MutationResult) null));

        DocumentResponse response = service.importTxt(txtFile());

        assertEquals(2, response.chunkCount());
        assertFalse(response.duplicated());
        verify(vectorStore).add(anyList());
        ArgumentCaptor<DeleteParam> captor = ArgumentCaptor.forClass(DeleteParam.class);
        verify(milvusClient).delete(captor.capture());
        String expr = captor.getValue().getExpr();
        assertTrue(expr.contains("doc_md5"), "删除表达式应包含 doc_md5: " + expr);
        assertTrue(expr.contains("chunk_pipeline_version"), "删除表达式应包含版本字段: " + expr);
        assertTrue(expr.contains("!="), "删除表达式应排除当前版本: " + expr);
    }

    @Test
    void deleteFailureDoesNotFailImport() {
        DocumentService service = serviceWithCounts(0, 1);
        when(cleaningTextSplitter.apply(anyList())).thenReturn(List.of(new Document("块", Map.of())));
        when(milvusClient.delete(any(DeleteParam.class)))
                .thenReturn(R.failed(R.Status.UnexpectedError, "mock delete failure"));

        DocumentResponse response = assertDoesNotThrow(() -> service.importTxt(txtFile()));

        assertEquals(1, response.chunkCount());
        assertFalse(response.duplicated());
        verify(vectorStore).add(anyList());
    }

    @Test
    void duplicateSkipsVectorStoreAdd() {
        DocumentService service = serviceWithCounts(1, 1);

        DocumentResponse response = service.importTxt(txtFile());

        assertEquals(0, response.chunkCount());
        assertTrue(response.duplicated());
        verify(vectorStore, never()).add(anyList());
        verify(cleaningTextSplitter, never()).apply(anyList());
    }

    @Test
    void newDocumentImportsWithoutDelete() {
        DocumentService service = serviceWithCounts(0, 0);
        when(cleaningTextSplitter.apply(anyList())).thenReturn(List.of(new Document("块", Map.of())));

        DocumentResponse response = service.importTxt(txtFile());

        assertEquals(1, response.chunkCount());
        assertFalse(response.duplicated());
        verify(vectorStore).add(anyList());
        verify(milvusClient, never()).delete(any(DeleteParam.class));
    }
}
