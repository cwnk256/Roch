package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.*;
import com.wuyunbin.rag.service.export.DocumentExporter;
import com.wuyunbin.rag.service.export.ExportedFile;
import com.wuyunbin.rag.service.generation.EvidenceIntegrityValidator;
import com.wuyunbin.rag.service.generation.model.GeneratedDocument;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportTemplatesTest {
    private ReportConfiguration resolve(List<DocumentTaskRequest.SectionSelection> sections) {
        return ReportTemplates.resolve(new DocumentTaskRequest(DocumentTaskType.REPORT, List.of("a"),
                "总要求", "ANALYSIS", "1", sections, null, null));
    }

    @Test
    void freezesRequestedOrderAndCombinesBothLevelsOfInstructions() {
        var config = resolve(List.of(new DocumentTaskRequest.SectionSelection("gaps", "说明缺失周期"),
                new DocumentTaskRequest.SectionSelection("findings", "")));
        var plan = config.toPlan();
        assertEquals(List.of("资料缺口", "分析结论"), plan.sections().stream().map(section -> section.title()).toList());
        assertTrue(plan.sections().getFirst().purpose().contains("说明缺失周期"));
        assertTrue(plan.sections().getFirst().purpose().contains("总要求"));
    }

    @Test
    void rejectsMissingRequiredDuplicateAndUnknownSections() {
        var findings = new DocumentTaskRequest.SectionSelection("findings", null);
        assertThrows(IllegalArgumentException.class, () -> resolve(List.of()));
        assertThrows(IllegalArgumentException.class, () -> resolve(List.of(findings, findings)));
        assertThrows(IllegalArgumentException.class, () -> resolve(List.of(findings,
                new DocumentTaskRequest.SectionSelection("other", null))));
    }

    @Test
    void retryCopiesHistoricalConfigurationWithoutResolvingLatestTemplate() {
        var store = new InMemoryDocumentTaskStore();
        var runner = mock(DocumentTaskRunner.class);
        var validator = mock(DocumentTaskValidator.class);
        var service = new DocumentTaskService(validator, store, mock(GeneratedDocumentStorage.class),
                mock(TaskArtifactService.class), runner, new DocumentTaskProperties());
        var original = DocumentTask.create(DocumentTaskType.REPORT, List.of("a"), "旧总要求");
        var archived = new ReportConfiguration("ANALYSIS", "retired", "旧模板",
                List.of(new ReportConfiguration.Section("old-only", "历史章节", true, "旧要求")),
                original.getInstruction(), new ReportExportOptions(ReportExportOptions.CitationStyle.FILE_LOCATION,
                false, true), List.of(), null, null);
        original.setConfiguration(archived);
        original.markFailed("retry");
        store.save(original);
        var retry = service.retry(original.getTaskId());
        assertNotEquals(original.getTaskId(), retry.taskId());
        assertEquals(archived, retry.configuration());
        assertEquals(DocumentTaskStatus.FAILED, store.find(original.getTaskId()).orElseThrow().getStatus());
        verify(runner).submit(retry.taskId());
    }

    @Test
    void snapshotsRetrievalSettingsAndRetriesUseTheSavedBudget() {
        var settings = new DocumentTaskProperties();
        settings.setRetrievalStrategy("full");
        settings.setMaxContextChars(14000);
        var store = new InMemoryDocumentTaskStore();
        var service = new DocumentTaskService(mock(DocumentTaskValidator.class), store,
                mock(GeneratedDocumentStorage.class), mock(TaskArtifactService.class),
                mock(DocumentTaskRunner.class), settings);
        var original = service.createAndExecute(new DocumentTaskRequest(DocumentTaskType.REPORT, List.of("a"), "查找证据"));
        assertEquals(new ReportRetrievalSettings("full", 8, 0.5, 14000, 8000),
                original.configuration().retrieval());
        var failed = store.find(original.taskId()).orElseThrow();
        failed.markFailed("模型超时");
        store.save(failed);

        settings.setRetrievalStrategy("baseline");
        settings.setMaxContextChars(6000);

        assertEquals(original.configuration(), service.retry(original.taskId()).configuration());
    }

    @Test
    void rejectsOtherTaskTemplatesAndUnavailableVersions() {
        for (var request : List.of(
                new DocumentTaskRequest(DocumentTaskType.SUMMARY, List.of("a"), "生成", "ANALYSIS", "1", null, null, null),
                new DocumentTaskRequest(DocumentTaskType.REPORT, List.of("a"), "生成", "ANALYSIS", "9", null, null, null),
                new DocumentTaskRequest(DocumentTaskType.REPORT, List.of("a"), "生成", null, "1", null, null, null),
                new DocumentTaskRequest(DocumentTaskType.REPORT, List.of("a"), "生成", "other", null, null, null, null))) {
            assertThrows(IllegalArgumentException.class, () -> ReportTemplates.resolve(request));
        }
    }

    @Test
    void repeatedExportsReadOneSnapshotAndRegisterDistinctArtifactsWithoutGenerating() {
        var generator = mock(DocumentTaskExecutor.class);
        var model = mock(ChatModel.class);
        var exporter = mock(DocumentExporter.class);
        var results = mock(GeneratedDocumentStorage.class);
        var artifacts = mock(TaskArtifactService.class);
        var tasks = new InMemoryDocumentTaskStore();
        var runner = new DocumentTaskRunner(tasks, generator, model, exporter, results, artifacts,
                mock(EvidenceIntegrityValidator.class), mock(TaskProgressPublisher.class),
                mock(com.wuyunbin.rag.service.generation.OriginalPassages.class), Runnable::run);
        var service = new DocumentTaskService(mock(DocumentTaskValidator.class), tasks, results, artifacts, runner,
                new DocumentTaskProperties());
        var task = DocumentTask.create(DocumentTaskType.REPORT, List.of("a"), "生成");
        tasks.save(task);
        var doc = new GeneratedDocument("历史报告", "摘要", List.of(), List.of());
        when(results.read(task.getTaskId())).thenReturn(doc);
        var file = new ExportedFile("历史报告.pdf", java.nio.file.Path.of("历史报告.pdf"), "application/pdf");
        var options = ReportExportOptions.DEFAULT;
        when(exporter.export(same(doc), anyString(), same(options))).thenReturn(file);
        for (int i = 0; i < 2; i++) service.reExportPdf(task.getTaskId(), options);
        var stems = ArgumentCaptor.forClass(String.class);
        verify(exporter, times(2)).export(same(doc), stems.capture(), same(options));
        assertEquals(2L, stems.getAllValues().stream().distinct().count());
        verify(artifacts, times(2)).register(task.getTaskId(), file);
        verify(results, never()).save(anyString(), any());
        verifyNoInteractions(generator, model);
    }

    @Test
    void canSpecifyPartialExportPreferencesWithoutChangingAutomaticOutline() {
        var options = new ReportExportOptions(ReportExportOptions.CitationStyle.FILE_LOCATION, false, null);
        var config = ReportTemplates.resolve(new DocumentTaskRequest(DocumentTaskType.SUMMARY, List.of("a"), "生成",
                null, null, null, options, null));
        assertNull(config.templateId());
        assertEquals(ReportExportOptions.CitationStyle.FILE_LOCATION, config.exportOptions().citationStyle());
        assertFalse(config.exportOptions().includeToc());
        assertTrue(config.exportOptions().includeEvidenceAppendix());
    }

    @Test
    void legacyRequestKeepsAutomaticOutline() {
        var config = ReportTemplates.resolve(new DocumentTaskRequest(DocumentTaskType.REPORT, List.of("a"), "生成"));
        assertNull(config.templateId());
        assertTrue(config.sections().isEmpty());
    }
}
