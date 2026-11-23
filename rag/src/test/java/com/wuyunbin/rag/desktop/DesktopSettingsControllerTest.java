package com.wuyunbin.rag.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyunbin.rag.persistence.task.DocumentTaskJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DesktopSettingsControllerTest {
    @TempDir
    Path directory;

    @Test
    void firstSaveProbesEvenTheDefaultEmbeddingConnection() throws Exception {
        var store = new DesktopSettingsStore(directory.resolve("settings.json"), new ObjectMapper());
        var models = mock(LmStudioClient.class);
        var settings = store.get();
        when(models.test(settings.modelOrigin(), settings.embeddingModel(), "embedding"))
                .thenReturn(new LmStudioClient.TestResult("ready", settings.embeddingDimension() + 1));
        var controller = new DesktopSettingsController(store, mock(DesktopIndexBinding.class),
                new DesktopActivity(mock(DocumentTaskJpaRepository.class)), models);

        assertThrows(IllegalArgumentException.class, () -> controller.save(settings));
        assertFalse(java.nio.file.Files.exists(directory.resolve("settings.json")));
    }
}
