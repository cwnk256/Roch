package com.wuyunbin.rag.service.generation.model;

import java.util.List;
import java.util.Map;
import com.wuyunbin.rag.dto.ReportConfiguration;
import com.wuyunbin.rag.service.comparison.ComparisonResult;

/**
 * 最终结构化文档：标题 + 摘要 + 有序章节 + 去重后的参考资料。
 *
 * <p>这是生成层与导出层之间的唯一数据契约：LLM 只产出本对象，
 * 再由 {@code DocumentExporter}（PdfExporter / 未来的 MarkdownExporter）渲染成文件，
 * 业务不直接耦合 PDF。</p>
 */
public record GeneratedDocument(

        String title,

        String summary,

        List<GeneratedSection> sections,

        List<Evidence> references,
        String schemaVersion,
        List<String> summaryClaimIds,
        List<Claim> summaryClaims,
        List<Evidence> evidenceSnapshots,
        ComparisonResult comparison,
        ReportConfiguration configuration,
        Map<String, CurrentSource> currentSources
) {
    public GeneratedDocument {
        schemaVersion = schemaVersion == null ? "v1" : schemaVersion;
        summaryClaimIds = summaryClaimIds == null ? List.of() : List.copyOf(summaryClaimIds);
        summaryClaims = summaryClaims == null ? List.of() : List.copyOf(summaryClaims);
        evidenceSnapshots = evidenceSnapshots == null ? List.of() : List.copyOf(evidenceSnapshots);
        currentSources = currentSources == null ? Map.of() : Map.copyOf(currentSources);
    }

    public GeneratedDocument(String title, String summary, List<GeneratedSection> sections, List<Evidence> references) {
        this(title, summary, sections, references, "v1", List.of(), List.of(), List.of(), null, null, Map.of());
    }

    public GeneratedDocument withComparison(ComparisonResult result) {
        return new GeneratedDocument(title, summary, sections, references, schemaVersion, summaryClaimIds,
                summaryClaims, evidenceSnapshots, result, configuration, currentSources);
    }

    public GeneratedDocument withConfiguration(ReportConfiguration config) {
        return new GeneratedDocument(title, summary, sections, references, schemaVersion, summaryClaimIds,
                summaryClaims, evidenceSnapshots, comparison, config, currentSources);
    }

    public GeneratedDocument withCurrentSources(Map<String, CurrentSource> sources) {
        return new GeneratedDocument(title, summary, sections, references, schemaVersion, summaryClaimIds,
                summaryClaims, evidenceSnapshots, comparison, configuration, sources);
    }
}
