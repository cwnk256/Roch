package com.wuyunbin.rag.service.ocr;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OcrEngineClientTest {
    @Test
    void restrictsDocumentImagesToLocalHttp() {
        for (String endpoint : List.of("http://127.0.0.1:18765", "http://localhost:18765/", "http://[::1]:18765")) {
            assertDoesNotThrow(() -> new OcrEngineClient(endpoint, 180));
        }
        for (String endpoint : List.of("https://127.0.0.1:18765", "http://192.168.1.10:18765",
                "http://localhost.example.com:18765", "http://127.0.0.1@192.168.1.10:18765")) {
            assertThrows(IllegalArgumentException.class, () -> new OcrEngineClient(endpoint, 180));
        }
    }
}
