export type TaskType = 'SUMMARY' | 'REPORT' | 'COMPARE'

export interface ExportOptions {
    citationStyle: 'NUMBERED' | 'FILE_LOCATION'
    includeToc: boolean
    includeEvidenceAppendix: boolean
}

export const defaultExport = (): ExportOptions => ({
    citationStyle: 'NUMBERED', includeToc: true, includeEvidenceAppendix: true
})

export interface TemplateSection {
    sectionId: string
    title: string
    required: boolean
    instruction: string
}

export interface ReportTemplate {
    templateId: string
    templateVersion: string
    title: string
    type: TaskType
    sections: TemplateSection[]
}

export interface OcrEvidence {
    region?: number[] | null
    table?: {row: number; column: number} | null
    reviewRequired: boolean
}

export interface Evidence {
    documentId: string
    fileName: string
    chunkId: string
    section?: string
    pageStart?: number
    pageEnd?: number
    evidenceId?: string
    quote?: string
    attemptedQuote?: string
    sourceStatus?: string
    chunkIndex?: number
    nativeLocation?: string
    ocr?: OcrEvidence | null
}

export interface Claim {
    claimId: string
    text: string
    evidenceIds: string[]
    supportStatus: string
    reason: string
    automatic: boolean
}

export interface CurrentSource {
    originalAvailable?: boolean | null
    chunkStatus: string
}

export interface ComparisonFact {
    claimId: string
    object?: string
    period?: string
    condition?: string
    metric?: string
    rawValue?: string
    unit?: string
    normalizedValue?: string
    normalizedUnit?: string
    verified: boolean
}

export interface Comparison {
    documents: {documentId: string; fileName: string}[]
    rows: {dimension: string; cells: {documentId: string; status: string; reason?: string; facts: ComparisonFact[]}[]}[]
    conclusions: {kind: string; dimension: string; text: string; claimIds: string[]}[]
}

export interface Report {
    schemaVersion?: string
    title: string
    summary: string
    sections: {title: string; content: string; claims?: Claim[]; evidences?: Evidence[]}[]
    references?: Evidence[]
    evidenceSnapshots?: Evidence[]
    summaryClaimIds?: string[]
    summaryClaims?: Claim[]
    comparison?: Comparison
    currentSources?: Record<string, CurrentSource>
    configuration?: {sections?: TemplateSection[]; title?: string; templateVersion?: string; exportOptions?: ExportOptions}
}

const labels: Record<string, string> = {
    SUPPORTED: '支持', PARTIAL: '部分支持', CONFLICT: '与原文冲突', INSUFFICIENT: '证据不足',
    MATCHED: '原文已匹配', SOURCE_UNAVAILABLE: '来源不可用', QUOTE_MISMATCH: '引文不匹配',
    UNCHANGED: '当前切片一致', CHANGED: '当前切片已变化', MISSING: '当前切片已移除', UNKNOWN: '当前状态未知',
    COMMON: '共同点', DIFFERENT: '差异', KEY_DATA: '关键数据', POSSIBLE_CONFLICT: '疑似矛盾', INCOMPARABLE: '口径不可比',
    FOUND: '已取证', NOT_FOUND: '本次未找到依据', RETRIEVAL_FAILED: '检索失败', EXTRACTION_FAILED: '提取失败', UNVERIFIED: '无法核验'
}

export const statusLabel = (value?: string) => value ? (labels[value] || value) : '未知'

export function locationOf(source: Evidence) {
    const parts: string[] = []
    if (source.pageStart != null) {
        const pages = source.pageEnd != null && source.pageEnd !== source.pageStart
            ? `${source.pageStart}–${source.pageEnd}` : String(source.pageStart)
        parts.push(`第 ${pages} 页`)
    }
    if (source.section?.trim()) parts.push(source.section)
    const nativeLocation = source.nativeLocation?.trim()
    if (nativeLocation && nativeLocation !== source.section?.trim()) parts.push(nativeLocation)
    if (!parts.length && source.chunkIndex != null) parts.push(`切片 ${source.chunkIndex + 1}`)
    return parts.join(' · ') || '位置未记录'
}

export function originalFileUrl(source: Evidence) {
    const page = source.pageStart && source.pageStart > 0 && (source.ocr || /\.pdf$/i.test(source.fileName))
        ? `#page=${source.pageStart}` : ''
    return `/api/documents/${encodeURIComponent(source.documentId)}/file${page}`
}

export function ocrPagePreview(source: Evidence) {
    const page = source.pageStart
    const region = source.ocr?.region
    if (!page || !Number.isInteger(page) || page < 1 || !region || region.length !== 4) return
    if (!region.every(value => Number.isFinite(value) && value >= 0 && value <= 1)) return
    const [left, top, right, bottom] = region
    if (left >= right || top >= bottom) return
    return {
        url: `/api/documents/${encodeURIComponent(source.documentId)}/pdf-pages/${page}`,
        highlight: {
            left: `${left * 100}%`, top: `${top * 100}%`,
            width: `${(right - left) * 100}%`, height: `${(bottom - top) * 100}%`
        }
    }
}

export function factFields(fact: ComparisonFact) {
    const valueWithUnit = (value?: string, unit?: string) => value && [value, unit].filter(Boolean).join(' ')
    return [
        ['对象', fact.object], ['期间', fact.period], ['条件', fact.condition], ['指标', fact.metric],
        ['原值', valueWithUnit(fact.rawValue, fact.unit)],
        ['换算值', valueWithUnit(fact.normalizedValue, fact.normalizedUnit)]
    ].filter(([, value]) => value?.trim()).map(([field, value]) => `${field}：${value}`).join(' · ')
}

export interface ReaderSource {
    snapshot: Evidence
    label: string
    current?: CurrentSource
}

export function readerSources(report: Report): Map<string, ReaderSource> {
    const sources = new Map<string, ReaderSource>()
    for (const snapshot of report.evidenceSnapshots ?? report.references ?? []) {
        if (!snapshot.evidenceId || sources.has(snapshot.evidenceId)) continue
        const number = `[${sources.size + 1}]`
        const location = `${snapshot.fileName || snapshot.documentId} · ${locationOf(snapshot)}`
        const label = report.configuration?.exportOptions?.citationStyle === 'FILE_LOCATION'
            ? `[${location}] ${number}` : `${number} ${location}`
        sources.set(snapshot.evidenceId, {snapshot, label, current: report.currentSources?.[snapshot.evidenceId]})
    }
    return sources
}
