package com.wuyunbin.rag.service;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentServiceMetadataTest {
    @Test
    void convertsMilvusJsonMetadataToMap() {
        JsonObject raw = new JsonObject();
        raw.addProperty("doc_md5", "document-1");
        raw.addProperty("file_name", "manual.txt");
        raw.addProperty("chunk_index", 3);

        Map<String, Object> metadata = DocumentService.metadataMap(raw);

        assertEquals("document-1", metadata.get("doc_md5"));
        assertEquals("manual.txt", metadata.get("file_name"));
        assertEquals(3, ((Number) metadata.get("chunk_index")).intValue());
    }
}
