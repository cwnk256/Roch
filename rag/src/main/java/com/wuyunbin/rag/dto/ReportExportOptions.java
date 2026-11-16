package com.wuyunbin.rag.dto;

/** Omitted export options inherit the saved result's defaults. */
public record ReportExportOptions(CitationStyle citationStyle, Boolean includeToc, Boolean includeEvidenceAppendix) {
    public enum CitationStyle { NUMBERED, FILE_LOCATION }

    public static final ReportExportOptions DEFAULT = new ReportExportOptions(CitationStyle.NUMBERED, true, true);

    public static ReportExportOptions merge(ReportExportOptions saved, ReportExportOptions override) {
        ReportExportOptions base = saved == null ? DEFAULT : saved;
        if (override == null) return base;
        return new ReportExportOptions(
                override.citationStyle == null ? base.citationStyle : override.citationStyle,
                override.includeToc == null ? base.includeToc : override.includeToc,
                override.includeEvidenceAppendix == null ? base.includeEvidenceAppendix : override.includeEvidenceAppendix);
    }
}
