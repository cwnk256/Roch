package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.dto.DocumentResponse;
import com.wuyunbin.rag.service.DocumentService;
import com.wuyunbin.rag.service.ocr.OcrImportJob;
import com.wuyunbin.rag.service.ocr.OcrImportService;
import com.wuyunbin.rag.service.ocr.OcrPdfPages;
import com.wuyunbin.rag.service.storage.DocumentStorage.StoredDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentOcrControllerTest {
    private final DocumentService documents = mock(DocumentService.class);
    private final OcrImportService imports = mock(OcrImportService.class);
    private final OcrPdfPages pdfPages = mock(OcrPdfPages.class);
    private final MockMultipartFile source = new MockMultipartFile("file", "scan.pdf", "application/pdf", new byte[]{1});
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(documents, null, null, null, imports, pdfPages)).build();
    }

    @Test
    void autoImportPreservesSynchronousNativeResponse() throws Exception {
        when(imports.startIfRequired(any(MultipartFile.class))).thenReturn(Optional.empty());
        when(documents.importPdf(any(MultipartFile.class))).thenReturn(new DocumentResponse(2, "document", false, "导入成功"));

        mvc.perform(multipart("/api/documents/pdf").file(source).param("ocr", "auto"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.md5").value("document"))
                .andExpect(jsonPath("$.chunkCount").value(2));
    }

    @Test
    void scanImportReturnsDurableJobAndCanBeRetriedById() throws Exception {
        UUID jobId = UUID.randomUUID();
        var queued = new OcrImportJob(jobId, "document", OcrImportJob.Status.QUEUED, 0, 0, null, "等待识别", null);
        var failed = new OcrImportJob(jobId, "document", OcrImportJob.Status.FAILED, 2, 3, 2, "识别失败", null);
        when(imports.startIfRequired(any(MultipartFile.class))).thenReturn(Optional.of(queued));
        when(imports.find(jobId)).thenReturn(Optional.of(failed));
        when(imports.retry(jobId)).thenReturn(Optional.of(queued));

        mvc.perform(multipart("/api/documents/pdf").file(source).param("ocr", "auto"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").value(jobId.toString()));
        mvc.perform(get("/api/documents/pdf-imports/{jobId}", jobId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.failedPage").value(2))
                .andExpect(jsonPath("$.status").value("FAILED"));
        mvc.perform(post("/api/documents/pdf-imports/{jobId}/retry", jobId))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED"));
        verifyNoInteractions(documents);
    }

    @Test
    void pagePreviewUsesOriginalPdfBytes() throws Exception {
        byte[] original = {1, 2};
        byte[] png = {3, 4};
        when(documents.loadSource("document")).thenReturn(new StoredDocument("document", "scan.pdf", original));
        when(pdfPages.preview(original, 2)).thenReturn(png);

        mvc.perform(get("/api/documents/{documentId}/pdf-pages/{page}", "document", 2))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(png));
    }
}
