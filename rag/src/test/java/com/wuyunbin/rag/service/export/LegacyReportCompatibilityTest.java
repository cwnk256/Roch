package com.wuyunbin.rag.service.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyunbin.rag.service.generation.model.GeneratedDocument;
import com.wuyunbin.rag.service.task.DocumentTaskProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyReportCompatibilityTest {
    @Test
    void reexportsTheSameSectionsFromAnArchivedV1Result(@TempDir Path output) throws Exception {
        var resources = new DefaultResourceLoader();
        var legacy = "classpath:traceability/legacy/";
        GeneratedDocument report;
        try (var input = resources.getResource(legacy + "result.json").getInputStream()) {
            report = new ObjectMapper().readValue(input, GeneratedDocument.class);
        }
        var options = new DocumentTaskProperties();
        options.setOutputDir(output.toString());
        options.setFontPath("classpath:fonts/NotoSansSC-Regular.ttf");
        var exported = new PdfExporter(options, resources).export(report, "legacy-review");
        try (var baseline = resources.getResource(legacy + "report.pdf").getInputStream();
             var before = Loader.loadPDF(baseline.readAllBytes());
             var after = Loader.loadPDF(Files.readAllBytes(exported.path()))) {
            var reader = new PDFTextStripper();
            String oldText = reader.getText(before).replaceAll("\\s+", "");
            String newText = reader.getText(after).replaceAll("\\s+", "");
            assertThat(report.sections()).isNotEmpty();
            for (var section : report.sections()) {
                var title = Objects.requireNonNull(section.title()).replaceAll("\\s+", "");
                assertThat(oldText).contains(title);
                assertThat(newText).contains(title);
            }
        }
    }
}
