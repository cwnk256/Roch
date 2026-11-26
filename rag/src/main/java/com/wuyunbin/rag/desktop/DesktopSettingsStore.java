package com.wuyunbin.rag.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyunbin.rag.dto.ReportGenerationSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class DesktopSettingsStore {
    private final Path path;
    private final ObjectMapper mapper;
    private final DesktopSettings.Connection activeConnection;
    private volatile Version version;

    private record Version(DesktopSettings saved, DesktopSettings generationSettings, boolean configured) {}

    DesktopSettingsStore(Path path, ObjectMapper mapper) throws IOException {
        this.path = path.toAbsolutePath().normalize();
        this.mapper = mapper;
        boolean configured = Files.exists(this.path);
        var settings = configured ? mapper.readValue(this.path.toFile(), DesktopSettings.class).validated()
                : DesktopSettings.defaults(this.path.getParent());
        activeConnection = settings.connection();
        version = new Version(settings, settings, configured);
    }

    public DesktopSettings get() {
        return version.saved();
    }

    public DesktopSettings generationSettings() {
        return version.generationSettings();
    }

    public ReportGenerationSnapshot generationSnapshot() {
        var settings = generationSettings();
        return new ReportGenerationSnapshot(settings.model(), settings.modelOrigin(), settings.temperature(),
                settings.topP(), settings.maxTokens(), settings.outputDirectory());
    }

    public DesktopSettings.Connection activeConnection() {
        return activeConnection;
    }

    boolean configured() {
        return version.configured();
    }

    boolean needsRestart() {
        return !version.saved().connection().equals(activeConnection);
    }

    synchronized void save(DesktopSettings candidate) throws IOException {
        Path output = Path.of(candidate.outputDirectory());
        Files.createDirectories(output);
        Path probe = Files.createTempFile(output, ".roch-write-", ".tmp");
        Files.delete(probe);

        Files.createDirectories(path.getParent());
        Path pending = Files.createTempFile(path.getParent(), "settings-", ".tmp");
        try {
            Files.write(pending, mapper.writeValueAsBytes(candidate), StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            String effectiveModel = activeConnection.modelOrigin().equals(candidate.modelOrigin())
                    ? candidate.model() : version.generationSettings().model();
            var effective = new DesktopSettings(effectiveModel, candidate.temperature(), candidate.topP(),
                    candidate.maxTokens(), candidate.outputDirectory(), activeConnection.modelOrigin(),
                    activeConnection.embeddingModel(), activeConnection.embeddingDimension());
            version = new Version(candidate, effective, true);
        } finally {
            Files.deleteIfExists(pending);
        }
    }
}
