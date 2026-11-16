package com.wuyunbin.rag.cleaning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkIdFactoryTest {

    private final String md5 = "d41d8cd98f00b204e9800998ecf8427e";
    private final String version = "v2";
    private final String blockType = "prose";
    private final String content = "这是第一段正文内容。";

    @Test
    void deterministicForSameInput() {
        String first = ChunkIdFactory.create(md5, version, blockType, 0, content);
        String second = ChunkIdFactory.create(md5, version, blockType, 0, content);
        assertEquals(first, second);
    }

    @Test
    void sixtyFourLowercaseHex() {
        String id = ChunkIdFactory.create(md5, version, blockType, 0, content);
        assertEquals(64, id.length());
        assertTrue(id.matches("[0-9a-f]{64}"), "应为 64 位小写十六进制: " + id);
    }

    @Test
    void differentContentDifferentId() {
        String first = ChunkIdFactory.create(md5, version, blockType, 0, content);
        String second = ChunkIdFactory.create(md5, version, blockType, 0, "另一段不同的正文内容。");
        assertNotEquals(first, second);
    }

    @Test
    void differentIndexDifferentId() {
        String first = ChunkIdFactory.create(md5, version, blockType, 0, content);
        String second = ChunkIdFactory.create(md5, version, blockType, 1, content);
        assertNotEquals(first, second);
    }
}
