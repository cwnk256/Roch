package com.wuyunbin.rag.dto;

import com.wuyunbin.rag.service.generation.model.ReportPlan;
import com.wuyunbin.rag.service.generation.model.ReportSectionPlan;
import java.util.List;

public record ReportConfiguration(String templateId, String templateVersion, String title, List<Section> sections,
                                  String instruction, ReportExportOptions exportOptions, List<String> comparisonDimensions,
                                  String model, String promptVersion) {
    public record Section(String sectionId, String title, boolean required, String instruction) {}

    public ReportConfiguration {
        sections = sections == null ? List.of() : List.copyOf(sections);
        comparisonDimensions = comparisonDimensions == null ? List.of() : List.copyOf(comparisonDimensions);
        exportOptions = ReportExportOptions.merge(null, exportOptions);
    }

    public ReportPlan toPlan() {
        return new ReportPlan(title, sections.stream().map(section -> new ReportSectionPlan(section.title(),
                section.instruction() + "\n总要求：" + instruction)).toList());
    }

    public ReportConfiguration withGenerationMetadata(String model, String promptVersion) {
        return new ReportConfiguration(templateId, templateVersion, title, sections, instruction,
                exportOptions, comparisonDimensions, model, promptVersion);
    }
}
