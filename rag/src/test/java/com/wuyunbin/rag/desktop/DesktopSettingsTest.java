package com.wuyunbin.rag.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DesktopSettingsTest {
    @TempDir
    Path directory;

    @Test
    void savesAbsoluteDirectoryAndReloadsSettingsWithoutChangingDefaults() throws Exception {
        Path file = directory.resolve("settings.json");
        var store = new DesktopSettingsStore(file, new ObjectMapper());
        assertEquals(directory.resolve("reports"), Path.of(store.get().outputDirectory()));
        var changed = new DesktopSettings("test-model", 0.4, 0.8, 512,
                directory.resolve("中文 报告").toString(), "http://localhost:1234/v1",
                "embedding-model", 1024);
        store.save(changed.validated());
        var restored = new DesktopSettingsStore(file, new ObjectMapper()).get();
        assertEquals("test-model", restored.model());
        assertEquals("http://127.0.0.1:1234", restored.modelOrigin());
        assertTrue(java.nio.file.Files.isDirectory(Path.of(restored.outputDirectory())));
    }

    @Test
    void rejectsRemoteAndMalformedModelEndpoints() {
        for (String origin : new String[]{"https://example.com", "http://127.0.0.1@evil.example:1234",
                "http://127.0.0.1:1234/path", "http://localhost:1234?x=1"}) {
            assertThrows(IllegalArgumentException.class, () -> DesktopSettings.normalizeOrigin(origin), origin);
        }
    }

    @Test
    void collectionIdentityDistinguishesModelDimensionAndOrigin() {
        var defaults = DesktopSettings.defaults(directory);
        var changed = new DesktopSettings(defaults.model(), null, null, null, defaults.outputDirectory(),
                defaults.modelOrigin(), defaults.embeddingModel(), defaults.embeddingDimension() + 1);
        assertNotEquals(defaults.connection().collectionName(), changed.connection().collectionName());
        assertEquals(defaults.connection().collectionName(), DesktopSettings.defaults(directory).connection().collectionName());
    }
}
