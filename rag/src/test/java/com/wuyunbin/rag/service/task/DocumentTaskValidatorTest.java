package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.DocumentMetadata;
import com.wuyunbin.rag.dto.DocumentStatus;
import com.wuyunbin.rag.dto.DocumentTaskRequest;
import com.wuyunbin.rag.dto.DocumentTaskType;
import com.wuyunbin.rag.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DocumentTaskValidator 单测：请求参数校验 + 资料存在性 / READY 状态校验。
 */
class DocumentTaskValidatorTest {

    private DocumentService documentService;
    private DocumentTaskValidator validator;

    private static DocumentMetadata metadata(String id, String status) {
        return new DocumentMetadata(id, id + ".txt", "TXT", id, 10L, 3,
                "heading-aware-v2", status, null);
    }

    // ----------------------- validateRequest -----------------------

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        validator = new DocumentTaskValidator(documentService);
    }

    @Test
    void validate_nullRequest_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validateRequest(null));
        assertTrue(ex.getMessage().contains("请求体"));
    }

    @Test
    void validate_nullType_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateRequest(
                        new DocumentTaskRequest(null, List.of("d1"), "总结一下")));
    }

    @Test
    void validate_nullOrEmptyDocumentIds_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateRequest(
                        new DocumentTaskRequest(DocumentTaskType.SUMMARY, null, "总结一下")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateRequest(
                        new DocumentTaskRequest(DocumentTaskType.SUMMARY, List.of(), "总结一下")));
    }

    @Test
    void validate_blankDocumentId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateRequest(
                        new DocumentTaskRequest(DocumentTaskType.SUMMARY, List.of("d1", "  "), "总结一下")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateRequest(
                        new DocumentTaskRequest(DocumentTaskType.SUMMARY,
                                java.util.Arrays.asList("d1", null), "总结一下")));
    }

    @Test
    void validate_blankInstruction_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateRequest(
                        new DocumentTaskRequest(DocumentTaskType.REPORT, List.of("d1"), null)));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateRequest(
                        new DocumentTaskRequest(DocumentTaskType.REPORT, List.of("d1"), "   ")));
    }

    @Test
    void validate_tooLongInstruction_throws() {
        String tooLong = "很".repeat(4001);
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateRequest(
                        new DocumentTaskRequest(DocumentTaskType.REPORT, List.of("d1"), tooLong)));
    }

    // ----------------------- validateDocumentsExist -----------------------

    @Test
    void validate_validRequest_passes() {
        assertDoesNotThrow(() -> validator.validateRequest(
                new DocumentTaskRequest(DocumentTaskType.SUMMARY, List.of("d1", "d2"), "总结这两份资料")));
    }

    @Test
    void validateDocuments_notFound_throws() {
        when(documentService.getDocument("missing")).thenReturn(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator.validateDocumentsExist(List.of("missing")));
        assertTrue(ex.getMessage().contains("文档不存在"));
    }

    @Test
    void validateDocuments_notReady_throws() {
        when(documentService.getDocument("d1")).thenReturn(metadata("d1", DocumentStatus.PROCESSING.name()));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator.validateDocumentsExist(List.of("d1")));
        assertTrue(ex.getMessage().contains("未就绪"));
    }

    @Test
    void validateDocuments_allReady_passes() {
        when(documentService.getDocument("d1")).thenReturn(metadata("d1", DocumentStatus.READY.name()));
        when(documentService.getDocument("d2")).thenReturn(metadata("d2", DocumentStatus.READY.name()));

        assertDoesNotThrow(() -> validator.validateDocumentsExist(List.of("d1", "d2")));
        verify(documentService).getDocument("d1");
        verify(documentService).getDocument("d2");
    }
}
