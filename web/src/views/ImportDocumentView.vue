<script lang="ts" setup>
import {computed, onMounted, ref, watch} from 'vue'
import {CheckCircle2, FileUp} from 'lucide-vue-next'
import {api} from '../api'
import type {DocumentImportResult, PdfImportJob} from '../types'

const file = ref<File | null>(null)
const kind = ref('')
const loading = ref(false)
const error = ref('')
const result = ref<DocumentImportResult | null>(null)
const job = ref<PdfImportJob | null>(null)
const jobId = ref('')
const statusError = ref('')
const storageKey = 'roch.pdfImportJobId'
const uploadKinds: Record<string, string> = {txt: '', docx: 'word', pdf: 'pdf', xlsx: 'excel', xls: 'excel'}
const jobLabels: Record<PdfImportJob['status'], string> = {
  QUEUED: '等待 OCR 识别',
  RUNNING: '正在识别扫描页',
  INDEXING: '正在建立文档索引',
  SUCCEEDED: 'OCR 导入完成',
  FAILED: 'OCR 导入失败'
}
const activeJob = computed(() => Boolean(jobId.value) && job.value?.status !== 'SUCCEEDED' && job.value?.status !== 'FAILED')
const fileControlsDisabled = computed(() => loading.value || (activeJob.value && !statusError.value))

onMounted(() => {
  try {
    jobId.value = sessionStorage.getItem(storageKey) || ''
    if (jobId.value) kind.value = 'pdf'
  } catch {
    // 浏览器禁用会话存储时，仍可在当前页面跟踪任务。
  }
})

watch([jobId, () => job.value?.status], ([id, status]) => {
  try {
    if (id && status !== 'SUCCEEDED') sessionStorage.setItem(storageKey, id)
    else sessionStorage.removeItem(storageKey)
  } catch {
    // 任务已保存在服务端。
  }
})

watch([jobId, activeJob], ([id, active], _, onCleanup) => {
  if (!active) return
  const controller = new AbortController()
  let timer: number
  onCleanup(() => {
    controller.abort()
    window.clearTimeout(timer)
  })
  const poll = async () => {
    try {
      const current = await api.pdfImport(id, controller.signal)
      if (controller.signal.aborted) return
      job.value = current
      statusError.value = ''
      if (current.status === 'FAILED' || current.status === 'SUCCEEDED') return
    } catch (problem) {
      if (controller.signal.aborted) return
      statusError.value = String(problem)
    }
    timer = window.setTimeout(poll, 1800)
  }
  void poll()
})

function choose(event: Event) {
  const input = event.target as HTMLInputElement
  const selectedFile = input.files?.[0]
  if (!selectedFile) return
  file.value = selectedFile
  kind.value = uploadKinds[selectedFile.name.split('.').pop()?.toLowerCase() || ''] ?? kind.value
  input.value = ''
  error.value = ''
  result.value = null
  job.value = null
  jobId.value = ''
  statusError.value = ''
}

async function upload() {
  const selectedFile = file.value
  if (!selectedFile || loading.value || jobId.value) return
  loading.value = true
  error.value = ''
  result.value = null
  try {
    if (kind.value === 'pdf') {
      const submission = await api.importPdf(selectedFile)
      if (submission.kind === 'ocr') {
        job.value = submission.job
        jobId.value = submission.job.jobId
      } else {
        result.value = submission.result
      }
    } else {
      result.value = await api.upload(selectedFile, kind.value)
    }
  } catch (problem) {
    error.value = String(problem)
  } finally {
    loading.value = false
  }
}

async function retry() {
  if (loading.value || job.value?.status !== 'FAILED') return
  loading.value = true
  error.value = ''
  try {
    job.value = await api.retryPdfImport(job.value.jobId)
  } catch (problem) {
    error.value = String(problem)
  } finally {
    loading.value = false
  }
}
</script>
<template>
  <section class="page-intro">
    <div><p class="eyebrow">INGESTION / SOURCE</p>
      <h2>导入文档</h2>
      <p>将本地资料加入知识库，导入后即可检索和用于报告生成。</p></div>
  </section>
  <div class="panel" style="padding:24px;max-width:760px">
    <div class="file-drop">
      <FileUp :size="30" aria-hidden="true"/>
      <strong>{{ file?.name || '选择一个文档文件' }}</strong>
      <span>支持 TXT、DOCX、PDF、XLSX、XLS</span><br>
      <label class="button file-picker" :class="{disabled: fileControlsDisabled}">
        <input accept=".txt,.docx,.pdf,.xlsx,.xls" type="file" :disabled="fileControlsDisabled" @change="choose"/>
        选择文件
      </label>
    </div>
    <div class="form-grid" style="margin-top:18px">
      <div class="field">
        <label for="import-file-kind">文件类型</label>
        <select id="import-file-kind" v-model="kind" :disabled="fileControlsDisabled">
          <option value="">TXT</option>
          <option value="word">Word / DOCX</option>
          <option value="pdf">PDF</option>
          <option value="excel">Excel</option>
        </select>
      </div>
      <div style="display:flex;align-items:end">
        <button :disabled="!file || loading || Boolean(jobId)" class="button primary" @click="upload">
          <FileUp :size="15" aria-hidden="true"/>
          {{ loading ? '导入中…' : '开始导入' }}
        </button>
      </div>
    </div>
    <p v-if="kind === 'pdf' && !jobId" class="import-hint">上传后自动检测并识别扫描页。</p>
    <div v-if="error" class="notice error import-notice" role="alert">{{ error }}</div>
    <div v-if="job" class="notice import-notice" :class="{error: job.status === 'FAILED'}">
      <div role="status">
        <strong>{{ jobLabels[job.status] }}</strong>
        <span v-if="job.status === 'FAILED' && job.failedPage" class="import-detail">失败页：第 {{ job.failedPage }} 页</span>
        <span v-else-if="job.totalPages > 0" class="import-detail">{{ job.currentPage }} / {{ job.totalPages }} 页</span>
        <p v-if="job.message" class="import-detail">{{ job.message }}</p>
        <p v-if="job.result && !job.result.duplicated" class="import-detail">已建立 {{ job.result.chunkCount }} 个切片。</p>
      </div>
      <button v-if="job.status === 'FAILED'" class="button retry-button" :disabled="loading" @click="retry">
        {{ loading ? '正在重试…' : '重试 OCR 导入' }}
      </button>
    </div>
    <div v-else-if="activeJob && !statusError" class="notice import-notice" role="status">正在查询 OCR 任务…</div>
    <div v-if="statusError" class="notice error import-notice" role="status">暂时无法更新 OCR 进度，将自动重试。{{ statusError }}</div>
    <div v-if="result" class="notice import-notice" role="status">
      <CheckCircle2 :size="15" style="vertical-align:-3px" aria-hidden="true"/>
      {{ result.message }}
      <span v-if="!result.duplicated"> 已建立 {{ result.chunkCount }} 个切片。</span>
    </div>
  </div>
</template>

<style scoped>
.file-picker { margin-top: 18px; cursor: pointer }
.file-picker:focus-within { outline: 2px solid #1c7457; outline-offset: 2px }
.file-picker.disabled { cursor: not-allowed; opacity: .55 }
.file-picker input { display: block; position: absolute; width: 1px; height: 1px; clip-path: inset(50%); overflow: hidden }
.file-drop strong { overflow-wrap: anywhere }
.import-hint { color: #668077; font-size: 12px; margin: 10px 0 0 }
.import-notice { margin-top: 16px }
span.import-detail { margin-left: 8px }
p.import-detail { margin: 5px 0 0 }
.retry-button { margin-top: 10px }
</style>
