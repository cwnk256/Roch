<script setup lang="ts">
import {computed, nextTick, ref, watch, useId} from 'vue'
import PaginationControl from './PaginationControl.vue'
import {paginateItems} from '../lib/pagination'
import {ChevronRight} from 'lucide-vue-next'
import ClaimEvidence from './ClaimEvidence.vue'
import {factFields, locationOf, readerSources, statusLabel, type Report} from '../lib/report'

const props = defineProps<{report: Report}>()
const sources = computed(() => readerSources(props.report))
const claims = computed(() => {
  const allClaims = props.report.sections.flatMap(section => section.claims || []).concat(props.report.summaryClaims || [])
  return new Map(allClaims.map(claim => [claim.claimId, claim]))
})
const summaryIds = computed(() => [...new Set([
  ...props.report.summaryClaimIds || [], ...(props.report.summaryClaims || []).map(claim => claim.claimId)
])])
const conclusionSections: Record<string, string> = {
  COMMON: 'commonalities', DIFFERENT: 'differences', INCOMPARABLE: 'differences', KEY_DATA: 'key_data', POSSIBLE_CONFLICT: 'conflicts'
}
const sections = computed(() => props.report.sections.map((section, index) => {
  const id = props.report.configuration?.sections?.[index]?.sectionId
  const conclusions = props.report.comparison?.conclusions.filter(item => conclusionSections[item.kind] === id)
  return {...section, id, conclusions}
}))

const initialSection = () => props.report.comparison
  ? sections.value.findIndex(section => section.id === 'matrix') : -1
const sectionIndex = ref(initialSection())
const itemPage = ref(0)
const dimensionIndex = ref(0)
const scrollArea = ref<HTMLElement | null>(null)
const sectionSelectId = useId()
const dimensionSelectId = useId()
const pageSize = 4
const selectedSection = computed(() => sections.value[sectionIndex.value])
const selectedRows = computed(() => {
  const rows = props.report.comparison?.rows || []
  return rows.slice(dimensionIndex.value, dimensionIndex.value + 1)
})
const itemTotal = computed(() => {
  if (sectionIndex.value === -1) return summaryIds.value.length
  const section = selectedSection.value
  if (!section) return 0
  if (section.id === 'matrix') return 0
  return props.report.comparison && section.id !== 'matrix'
    ? section.conclusions?.length || 0 : section.claims?.length || 0
})
const visibleSummaryIds = computed(() => paginateItems(summaryIds.value, pageSize, itemPage.value))
const visibleSections = computed(() => selectedSection.value ? [{
  ...selectedSection.value,
  claims: paginateItems(selectedSection.value.claims || [], pageSize, itemPage.value),
  conclusions: paginateItems(selectedSection.value.conclusions || [], pageSize, itemPage.value)
}] : [])

async function changePage(page: number) {
  itemPage.value = page
  await nextTick()
  if (scrollArea.value && getComputedStyle(scrollArea.value).overflowY === 'visible') {
    scrollArea.value.focus({preventScroll: true})
    scrollArea.value.scrollIntoView({block: 'start'})
  }
}

watch(() => props.report, () => { sectionIndex.value = initialSection(); itemPage.value = 0; dimensionIndex.value = 0 })
watch(sectionIndex, () => { itemPage.value = 0; dimensionIndex.value = 0 })
watch([sectionIndex, itemPage, dimensionIndex], () => { if (scrollArea.value) scrollArea.value.scrollTop = 0 }, {flush: 'post'})
</script>

<template>
  <article class="report">
    <div class="reader-controls">
      <div class="field"><label :for="sectionSelectId">阅读章节</label>
        <select :id="sectionSelectId" v-model="sectionIndex">
          <option :value="-1">摘要</option>
          <option v-for="(section,index) in sections" :key="section.id ?? index" :value="index">{{ section.title }}</option>
        </select>
      </div>
      <div v-if="selectedSection?.id==='matrix' && report.comparison?.rows.length" class="field">
        <label :for="dimensionSelectId">比较维度</label>
        <select :id="dimensionSelectId" v-model="dimensionIndex"><option v-for="(row,index) in report.comparison.rows" :key="index" :value="index">{{ row.dimension }}</option></select>
      </div>
    </div>
    <div ref="scrollArea" class="reader-scroll" tabindex="0" role="region" aria-label="报告正文" :key="sectionIndex">
    <p v-if="report.schemaVersion !== 'v2'" class="notice">历史报告，仅有章节来源。</p>
    <p v-if="report.configuration?.title" class="report-meta">
      {{ report.configuration.title }} · 模板版本 {{ report.configuration.templateVersion }}
    </p>
    <section v-if="sectionIndex===-1" class="report-summary">
    <h3>摘要</h3>
    <p v-if="!summaryIds.length || report.comparison" class="body">{{ report.summary }}</p>
    <ClaimEvidence v-for="id in visibleSummaryIds" :key="id" :claim="claims.get(id)" :sources="sources"/>
    </section>
    <section v-for="(section, index) in visibleSections" :key="section.id ?? index" class="report-section">

      <div v-if="report.comparison && section.id === 'matrix'" class="matrix" tabindex="0" aria-label="文档对比表，可横向滚动">
        <table :style="{minWidth: (120 + report.comparison.documents.length * 260) + 'px'}">
          <thead><tr>
            <th scope="col">维度</th>
            <th v-for="document in report.comparison.documents" :key="document.documentId" scope="col">
              {{ document.fileName || document.documentId }}
            </th>
          </tr></thead>
          <tbody><tr v-for="row in selectedRows" :key="row.dimension">
            <th scope="row">{{ row.dimension }}</th>
            <td v-for="cell in row.cells" :key="cell.documentId">
              <span class="cell-status" :data-status="cell.status">{{ statusLabel(cell.status) }}</span>
              <p v-if="cell.reason && cell.reason !== statusLabel(cell.status)" class="cell-reason">{{ cell.reason }}</p>
              <div v-for="fact in cell.facts" :key="fact.claimId" class="fact">
                <strong v-if="!fact.verified" class="warning">比较字段未核验</strong>
                <p>{{ factFields(fact) }}</p>
                <ClaimEvidence :claim="claims.get(fact.claimId)" :sources="sources" compact/>
              </div>
            </td>
          </tr></tbody>
        </table>
      </div>
      <template v-else-if="report.comparison && section.id">
        <p v-if="!section.conclusions?.length" class="notice">本次未找到可列入本节的比较结论。</p>
        <details v-for="(item, position) in section.conclusions" :key="position" class="conclusion">
          <summary><ChevronRight :size="15" class="conclusion-chevron disclosure-chevron" aria-hidden="true"/><span class="conclusion-text">{{ item.text }}</span><span class="cell-status" :data-status="item.kind">{{ statusLabel(item.kind) }}</span></summary>
          <div class="conclusion-sources">
          <ClaimEvidence v-for="id in item.claimIds" :key="id" :claim="claims.get(id)" :sources="sources"/>
          </div>
        </details>
      </template>
      <template v-else-if="section.claims?.length">
        <ClaimEvidence v-for="claim in section.claims" :key="claim.claimId" :claim="claim" :sources="sources"/>
      </template>
      <template v-else>
        <p class="body">{{ section.content }}</p>
        <p v-for="source in section.evidences" :key="source.evidenceId || source.chunkId">
          {{ source.fileName || source.documentId }} · {{ locationOf(source) }}
        </p>
      </template>
    </section>
    </div>
    <PaginationControl :page="itemPage" :size="pageSize" :total="itemTotal" @change="changePage"/>
  </article>
</template>

<style scoped>
.report { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow-wrap: anywhere; font-size: var(--font-body); line-height: 1.75; color: var(--text) }
.reader-controls { display: flex; gap: 14px; padding: 14px 0; flex: none; border-bottom: 1px solid var(--inset) }
.reader-controls .field { flex: 1; min-width: 0 }
.reader-controls select { width: 100%; min-width: 0 }
.reader-scroll { flex: 1; min-height: 0; padding: var(--space-4) 2px }
.reader-scroll:focus-visible { outline-offset: -2px }
@media (min-width: 901px) and (min-height: 650px) {
  .reader-scroll { overflow: auto; overscroll-behavior: contain; scrollbar-width: thin }
}
.report > .pagination { flex: none }
@media (max-width: 600px) { .reader-controls { gap: 8px; padding: 10px 0 } .reader-controls select { padding: 8px; font-size: 12px } }
.report-meta { color: var(--secondary); font-size: 11px; margin: 0 0 10px }
.report-summary { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-control); padding: var(--space-5) }
h3 { color: var(--text); font-size: 15px; margin: 0 0 14px }
.report-section { margin-top: 0; min-width: 0 }
.body { max-width: 70ch; white-space: pre-wrap; margin: 0 0 var(--space-3) }
.matrix { overflow-x: auto; border: 1px solid var(--line); border-radius: 8px }
.matrix:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px }
table { border-collapse: collapse; width: 100%; table-layout: fixed; min-width: 660px }
th, td { padding: 16px; text-align: left; vertical-align: top; border-right: 1px solid var(--inset); border-bottom: 1px solid var(--inset) }
th { font-size: 12px; color: var(--secondary); font-weight: 600; background: var(--inset) }
th:first-child { width: 120px }
th[scope="row"] { color: var(--text); background: var(--inset) }
tr:last-child td, tr:last-child th { border-bottom: 0 }
tr > :last-child { border-right: 0 }
.cell-status { display: inline-block; font-size: 11px; line-height: 1.6; padding: 3px 8px; border-radius: 20px; background: var(--inset); color: var(--secondary) }
.cell-status[data-status="FOUND"], .cell-status[data-status="COMMON"] { background: var(--success-surface); color: var(--success) }
.cell-status[data-status="UNVERIFIED"], .cell-status[data-status="INCOMPARABLE"] { background: var(--warning-surface); color: var(--warning) }
.cell-status[data-status="RETRIEVAL_FAILED"], .cell-status[data-status="EXTRACTION_FAILED"], .cell-status[data-status="POSSIBLE_CONFLICT"] { background: var(--danger-surface); color: var(--danger) }
.cell-reason { color: var(--secondary); font-size: 12px }
.fact { margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--inset) }
.fact p { margin: 5px 0; font-size: 12px }
.warning { color: var(--warning); font-weight: 500; font-size: 11px }
.conclusion { margin: 8px 0; border: 1px solid var(--line); border-radius: 8px }
.conclusion > summary { display: flex; align-items: flex-start; gap: 10px; padding: 14px; cursor: pointer; list-style: none }
.conclusion > summary::-webkit-details-marker { display: none }
.conclusion > summary:hover { background: var(--inset); border-radius: 8px }
.conclusion > summary:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; border-radius: 8px }
.conclusion-chevron { flex: none; margin-top: 5px; color: var(--secondary) }
.conclusion-text { flex: 1; min-width: 0 }
.conclusion > summary .cell-status { flex: none }
.conclusion-sources { padding: 0 14px 8px 39px }
@media (max-width: 600px) { .report-summary { padding: 14px } .conclusion > summary { flex-wrap: wrap } .conclusion-text { flex-basis: calc(100% - 25px) } .conclusion > summary .cell-status { margin-left: 25px } .conclusion-sources { padding: 0 12px 8px } }
</style>
