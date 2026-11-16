package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.*;
import com.wuyunbin.rag.dto.ReportConfiguration.Section;
import java.util.*;

public final class ReportTemplates {
    public record Template(String templateId, String templateVersion, String title, DocumentTaskType type, List<Section> sections) {}

    private static final Template ANALYSIS = new Template("ANALYSIS", "1", "分析报告", DocumentTaskType.REPORT, List.of(
            new Section("findings", "分析结论", true, "提炼选定资料支持的主要结论，逐条列出依据。"),
            new Section("key_data", "关键数据", false, "保留原值与单位，注明数据口径。"),
            new Section("gaps", "资料缺口", false, "说明哪些问题缺少充分依据。")));
    private static final Template COMPARISON = new Template("COMPARISON", "1", "文档对比", DocumentTaskType.COMPARE, List.of(
            new Section("matrix", "逐项对比", true, "按维度列出每份文档的事实和依据。"),
            new Section("commonalities", "共同点", false, "列出有一致依据的文档。"),
            new Section("differences", "差异", false, "说明不同文档的差异与可比条件。"),
            new Section("key_data", "关键数据", false, "保留原值、单位及规范化值。"),
            new Section("conflicts", "疑似矛盾", false, "仅列相同口径下不相容的陈述。")));
    private static final List<String> DEFAULT_DIMENSIONS = List.of("安全要求", "校准周期", "预算与支出");

    private ReportTemplates() {}

    public static List<Template> list() {
        return List.of(ANALYSIS, COMPARISON);
    }

    public static ReportConfiguration resolve(DocumentTaskRequest request) {
        boolean comparing = request.type() == DocumentTaskType.COMPARE;
        String templateId = request.templateId() == null && comparing ? COMPARISON.templateId() : request.templateId();
        var options = ReportExportOptions.merge(null, request.exportOptions());
        var dimensions = dimensions(request, comparing);
        if (templateId == null) {
            if (request.templateVersion() != null || request.sections() != null)
                throw new IllegalArgumentException("指定章节或模板版本前必须选择模板");
            return new ReportConfiguration(null, null, null, List.of(), request.instruction(), options, dimensions, null, null);
        }
        var template = list().stream().filter(value -> value.templateId().equals(templateId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知模板：" + templateId));
        if (request.type() != template.type()) throw new IllegalArgumentException("任务类型与模板不匹配");
        if (request.templateVersion() != null && !request.templateVersion().equals(template.templateVersion()))
            throw new IllegalArgumentException("模板版本已变更，请重新选择模板");
        var selected = request.sections() == null ? template.sections() : select(template, request.sections());
        return new ReportConfiguration(templateId, template.templateVersion(), template.title(), selected,
                request.instruction(), options, dimensions, null, null);
    }

    private static List<String> dimensions(DocumentTaskRequest request, boolean comparing) {
        List<String> requested = request.comparisonDimensions();
        if (!comparing) {
            if (requested != null && !requested.isEmpty()) throw new IllegalArgumentException("仅对比任务可指定比较维度");
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String dimension : requested == null ? DEFAULT_DIMENSIONS : requested) {
            if (dimension == null || dimension.isBlank() || dimension.strip().length() > 120 || !normalized.add(dimension.strip()))
                throw new IllegalArgumentException("比较维度不能为空、重复或超过 120 字符");
        }
        List<String> dimensions = List.copyOf(normalized);
        ComparisonLimits.validate(request.documentIds(), dimensions);
        return dimensions;
    }

    private static List<Section> select(Template template, List<DocumentTaskRequest.SectionSelection> requested) {
        List<Section> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var choice : requested) {
            if (choice == null || !seen.add(choice.sectionId())) throw new IllegalArgumentException("章节不能为空或重复");
            var section = template.sections().stream().filter(value -> value.sectionId().equals(choice.sectionId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("模板中不存在该章节：" + choice.sectionId()));
            String extra = choice.instruction() == null ? "" : choice.instruction().strip();
            if (extra.length() > 2000) throw new IllegalArgumentException("分章要求不能超过 2000 字符");
            String instruction = extra.isEmpty() ? section.instruction() : section.instruction() + "\n分章要求：" + extra;
            selected.add(new Section(section.sectionId(), section.title(), section.required(), instruction));
        }
        for (var section : template.sections()) {
            if (section.required() && !seen.contains(section.sectionId()))
                throw new IllegalArgumentException("缺少必选章节：" + section.title());
        }
        return List.copyOf(selected);
    }
}
