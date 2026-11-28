<script setup lang="ts">
import {computed} from 'vue'
import {ChevronRight, ExternalLink, FileText} from 'lucide-vue-next'
import OcrPagePreview from './OcrPagePreview.vue'
import {ocrPagePreview, originalFileUrl, statusLabel, type Claim, type ReaderSource} from '../lib/report'

const props = defineProps<{claim?: Claim; sources: Map<string, ReaderSource>; compact?: boolean}>()
const citations = computed(() => (props.claim?.evidenceIds || []).map(id => {
  const source = props.sources.get(id)
  const preview = source?.current?.originalAvailable ? ocrPagePreview(source.snapshot) : undefined
  return {id, source, preview}
}))
</script>

<template>
  <p v-if="!claim" class="notice">证据不足：未找到关联事实</p>
  <details v-else class="claim" :class="{'claim-compact': compact}" :data-status="claim.supportStatus || 'INSUFFICIENT'">
    <summary>
      <ChevronRight :size="15" class="claim-chevron disclosure-chevron" aria-hidden="true"/>
      <span class="claim-text">{{ compact ? '查看原文与判断' : claim.text }}</span>
      <span class="claim-status">{{ statusLabel(claim.supportStatus || 'INSUFFICIENT') }}</span>
    </summary>
    <div class="claim-detail">
    <p v-if="compact" class="claim-context">{{ claim.text }}</p>
    <p v-if="claim.reason" class="claim-reason"><span v-if="claim.automatic" class="judgment-label">自动判断</span>{{ claim.reason }}</p>
    <p v-if="!citations.length" class="missing-source">这条陈述没有可核对的引文。</p>
    <template v-for="{id, source, preview} in citations" :key="id">
      <div v-if="source" class="source">
        <div class="source-heading">
          <FileText :size="15" aria-hidden="true"/>
          <strong>{{ source.label }}</strong>
          <span v-if="source.snapshot.ocr" class="status-badge ocr-badge">OCR 识别</span>
        </div>
        <p class="source-state">
          {{ source.snapshot.sourceStatus ? statusLabel(source.snapshot.sourceStatus) : '来源未核验' }}
          <template v-if="source.current"> · {{ statusLabel(source.current.chunkStatus) }}</template>
          <template v-if="source.snapshot.ocr?.table?.row && source.snapshot.ocr.table.column">
            · 表格第 {{ source.snapshot.ocr.table.row }} 行第 {{ source.snapshot.ocr.table.column }} 列
          </template>
          <template v-else-if="source.snapshot.ocr?.table"> · 表格行列待确认</template>
        </p>
        <p v-if="source.snapshot.ocr?.reviewRequired" class="ocr-review">识别结果需复核，请核对扫描原页。</p>
        <blockquote v-if="source.snapshot.quote">{{ source.snapshot.quote }}</blockquote>
        <p v-else-if="source.snapshot.attemptedQuote">待核引文（未匹配） · {{ source.snapshot.attemptedQuote }}</p>
        <span v-if="source.current?.originalAvailable === false" class="missing-source">原文件当前不可用</span>
        <OcrPagePreview v-if="preview && source.snapshot.pageStart" :key="preview.url"
                        :page="source.snapshot.pageStart" :preview="preview"/>
        <a v-if="source.current?.originalAvailable" :href="originalFileUrl(source.snapshot)"
           target="_blank" rel="noopener" class="source-link">
          {{ source.snapshot.ocr && source.snapshot.pageStart ? `打开原 PDF 第 ${source.snapshot.pageStart} 页` : '打开原文件' }}
          <ExternalLink :size="12" aria-hidden="true"/>
        </a>
      </div>
      <p v-else>未找到证据快照</p>
    </template>
    </div>
  </details>
</template>

<style scoped>
.claim { margin: 8px 0; background: var(--surface); border: 1px solid var(--line); border-radius: 8px; overflow-wrap: anywhere }
summary { display: flex; align-items: flex-start; gap: 10px; padding: 14px; cursor: pointer; list-style: none; font-size: 13px; line-height: 1.75; color: var(--text) }
summary::-webkit-details-marker { display: none }
summary:hover { background: var(--inset); border-radius: 8px }
summary:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; border-radius: 8px }
.claim-chevron { margin-top: 4px; color: var(--accent) }
.claim-text { flex: 1; min-width: 0 }
.claim-status { flex: none; font-size: 11px; font-weight: 500; padding: 2px 8px; border-radius: 20px; background: var(--warning-surface); color: var(--warning); white-space: nowrap }
[data-status="SUPPORTED"] .claim-status { color: var(--success); background: var(--success-surface) }
[data-status="CONFLICT"] .claim-status { color: var(--danger); background: var(--danger-surface) }
.claim-detail { padding: 0 16px 16px 39px; font-size: var(--font-small); line-height: 1.75 }
.claim-compact .claim-detail { padding-inline: var(--space-2) }
.claim-compact .source { padding: var(--space-2) }
.claim-compact .source-heading { flex-wrap: wrap }
.claim-reason { margin: 0 0 12px; color: var(--secondary) }
.claim-context { margin: 0 0 12px; color: var(--text) }
.judgment-label { display: block; font-size: 11px; color: var(--secondary); margin-bottom: 3px }
.source { margin-top: 12px; border: 1px solid var(--line); padding: 14px; background: var(--inset); border-radius: 7px }
.source-heading { display: flex; align-items: flex-start; gap: 7px; color: var(--text) }
.source-heading svg { flex: none; margin-top: 4px }
.source-heading strong { font-size: 12px; font-weight: 600 }
.ocr-badge { flex: none; padding: 0 6px }
.source-state { margin: 5px 0 12px; font-size: 11px; color: var(--secondary) }
.ocr-review { margin: 5px 0 12px; color: var(--warning); font-size: 12px }
blockquote { max-width: 70ch; margin: 10px 0 12px; padding: 10px 13px; border-inline-start: 2px solid var(--accent); background: var(--surface); color: var(--text); white-space: pre-wrap }
.source-link { display: inline-flex; gap: 5px; align-items: center; color: var(--accent); text-decoration: none; font-size: 12px }
.source-link:hover { text-decoration: underline }
.missing-source { color: var(--warning); font-size: 12px }
@media (max-width: 600px) { summary { flex-wrap: wrap; gap: 7px; padding: 12px } .claim-text { flex-basis: calc(100% - 25px) } .claim-status { margin-left: 22px } .claim-detail { padding: 0 12px 12px } }
</style>
