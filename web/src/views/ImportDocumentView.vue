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
const dragDepth = ref(0)
const storageKey = 'roch.pdfImportJobId'
const uploadKinds = new Map([['txt', ''], ['docx', 'word'], ['pdf', 'pdf'], ['xlsx', 'excel'], ['xls', 'excel']])
const acceptedExtensions = Array.from(uploadKinds.keys(), extension => `.${extension}`).join(',')
const jobLabels: Record<PdfImportJob['status'], string> = {
  QUEUED: '等待 OCR 识别',
  RUNNING: '正在识别扫描页',
  INDEXING: '正在建立文档索引',
  SUCCEEDED: 'OCR 导入完成',
  FAILED: 'OCR 导入失败'
}
const activeJob = computed(() => Boolean(jobId.value) && job.value?.status !== 'SUCCEEDED' && job.value?.status !== 'FAILED')
const fileControlsDisabled = computed(() => loading.value || (activeJob.value && !statusError.value))
const isDropActive = computed(() => dragDepth.value > 0 && !fileControlsDisabled.value)

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

function detectUploadKind(selectedFile: File) {
  const extension = selectedFile.name.match(/\.([^.]+)$/)?.[1].toLowerCase()
  return extension ? uploadKinds.get(extension) : undefined
}

function selectFile(selectedFile: File, selectedKind = detectUploadKind(selectedFile)) {
  if (fileControlsDisabled.value) return
  file.value = selectedFile
  kind.value = selectedKind ?? kind.value
  error.value = ''
  result.value = null
  job.value = null
  jobId.value = ''
  statusError.value = ''
}

function choose(event: Event) {
  const input = event.currentTarget as HTMLInputElement
  const selectedFile = input.files?.[0]
  input.value = ''
  if (selectedFile) selectFile(selectedFile)
}

function enterDropArea(event: DragEvent) {
  if (!event.dataTransfer?.types.includes('Files')) return
  event.preventDefault()
  if (!fileControlsDisabled.value) dragDepth.value++
}

function allowFileDrop(event: DragEvent) {
  const transfer = event.dataTransfer
  if (!transfer?.types.includes('Files')) return
  event.preventDefault()
  transfer.dropEffect = fileControlsDisabled.value ? 'none' : 'copy'
}

function leaveDropArea() {
  dragDepth.value = Math.max(0, dragDepth.value - 1)
}

function dropFile(event: DragEvent) {
  const transfer = event.dataTransfer
  dragDepth.value = 0
  if (!transfer?.types.includes('Files')) return
  event.preventDefault()
  if (fileControlsDisabled.value) return

  if (transfer.files.length > 1) {
    error.value = '一次只能选择一个文件。'
    return
  }
  const droppedFile = transfer.files.item(0)
  const droppedItem = Array.from(transfer.items).find(item => item.kind === 'file')
  const selectedKind = droppedFile ? detectUploadKind(droppedFile) : undefined
  if (!droppedFile || selectedKind === undefined || droppedItem?.webkitGetAsEntry?.()?.isDirectory) {
    error.value = '请选择 TXT、DOCX、PDF、XLSX 或 XLS 文件。'
    return
  }
  selectFile(droppedFile, selectedKind)
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
  <div class="page-intro narrow-panel import-intro"><p>将本地资料加入知识库，用于检索、问答和生成报告。</p></div>
  <div class="panel panel-pad narrow-panel flow import-panel">
    <div class="file-picker-area" :class="{'is-dragging': isDropActive}"
         @dragenter="enterDropArea" @dragover="allowFileDrop" @dragleave="leaveDropArea" @drop="dropFile">
      <span class="file-picker-icon"><FileUp :size="25" aria-hidden="true"/></span>
      <strong aria-live="polite">{{ file?.name || '将文档拖到此处' }}</strong>
      <span class="muted">{{ isDropActive ? '松开即可选择文件' : '支持 TXT、DOCX、PDF、XLSX、XLS' }}</span>
      <label class="button file-picker" :class="{disabled: fileControlsDisabled}">
        <input :accept="acceptedExtensions" type="file" :disabled="fileControlsDisabled" @change="choose"/>
        选择文件
      </label>
    </div>
    <div class="form-grid">
      <div class="field">
        <label for="import-file-kind">文件类型</label>
        <select id="import-file-kind" v-model="kind" :disabled="fileControlsDisabled">
          <option value="">TXT</option>
          <option value="word">Word / DOCX</option>
          <option value="pdf">PDF</option>
          <option value="excel">Excel</option>
        </select>
      </div>
      <div class="form-action">
        <button :disabled="!file || loading || Boolean(jobId)" class="button primary" type="button" @click="upload">
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
      <CheckCircle2 :size="15" class="import-result-icon" aria-hidden="true"/>
      {{ result.message }}
      <span v-if="!result.duplicated"> 已建立 {{ result.chunkCount }} 个切片。</span>
    </div>
  </div>
</template>

<style scoped>
.import-intro, .import-panel { width: 100%; margin-inline: auto }
.file-picker-area { display: grid; justify-items: center; text-align: center; gap: var(--space-2); background: var(--inset); border: 1px dashed var(--control-line); padding: var(--space-5); border-radius: var(--radius-control) }
.file-picker-area.is-dragging { background: var(--selection); border-style: solid; border-color: var(--accent) }
.file-picker-area strong { max-width: 100%; overflow-wrap: anywhere }
.file-picker-area .muted { font-size: var(--font-small) }
.file-picker-icon { display: grid; place-items: center; width: 48px; height: 48px; border-radius: 14px; background: var(--selection); color: var(--accent) }
.file-picker { margin-top: var(--space-2); cursor: pointer }
.file-picker:focus-within { outline: 2px solid var(--accent); outline-offset: 2px }
.file-picker.disabled { cursor: not-allowed; opacity: .55 }
.file-picker input { display: block; position: absolute; width: 1px; height: 1px; clip-path: inset(50%); overflow: hidden }
.import-hint { color: var(--secondary); font-size: var(--font-small); margin: 0 }
.import-result-icon { vertical-align: -3px; color: var(--success) }
span.import-detail { margin-left: 8px }
p.import-detail { margin: 5px 0 0 }
.retry-button { margin-top: 10px }
</style>
