<script lang="ts" setup>
import {nextTick, onMounted, ref} from 'vue'
import {Eye, RefreshCw, Search, Trash2} from 'lucide-vue-next'
import {api} from '../api'
import type {DocumentMeta} from '../types'
import EmptyState from '../components/EmptyState.vue'
import LoadingBlock from '../components/LoadingBlock.vue'
import PaginationControl from '../components/PaginationControl.vue'
import StatusBadge from '../components/StatusBadge.vue'

const pageSize = 10
const docs = ref<DocumentMeta[]>([])
const keyword = ref('')
const page = ref(0)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const selected = ref<DocumentMeta | null>(null)
const chunks = ref<Record<string, unknown>[]>([])
const chunkPage = ref(0)
const chunkTotal = ref(0)
const chunkLoading = ref(false)
const chunkError = ref('')
const previewHeading = ref<HTMLHeadingElement | null>(null)
let chunkRequest = 0

async function load(nextPage = page.value) {
  loading.value = true
  error.value = ''
  try {
    const result = await api.listDocuments({page: nextPage, size: pageSize, keyword: keyword.value})
    docs.value = result.content || []
    page.value = result.page
    total.value = result.total
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

function search() {
  resetPreview()
  load(0)
}

function resetPreview(document: DocumentMeta | null = null) {
  chunkRequest++
  selected.value = document
  chunks.value = []
  chunkPage.value = 0
  chunkTotal.value = 0
  chunkLoading.value = false
  chunkError.value = ''
}

async function inspect(document: DocumentMeta) {
  resetPreview(document)
  void loadChunks(0)
  if (window.matchMedia('(width < 1200px)').matches) {
    await nextTick()
    previewHeading.value?.focus()
  }
}

async function loadChunks(nextPage: number): Promise<void> {
  const documentId = selected.value?.documentId
  if (!documentId) return
  const request = ++chunkRequest
  chunkLoading.value = true
  chunkError.value = ''
  try {
    const result = await api.chunks(documentId, {page: nextPage, size: pageSize})
    if (request !== chunkRequest) return
    const lastPage = Math.max(0, Math.ceil(result.total / pageSize) - 1)
    if (nextPage > lastPage) return loadChunks(lastPage)
    chunks.value = result.content || []
    chunkPage.value = result.page
    chunkTotal.value = result.total
  } catch (e) {
    if (request === chunkRequest) chunkError.value = String(e)
  } finally {
    if (request === chunkRequest) chunkLoading.value = false
  }
}

async function remove(document: DocumentMeta) {
  if (!confirm(`删除「${document.fileName}」？`)) return
  try {
    await api.deleteDocument(document.documentId)
    if (selected.value?.documentId === document.documentId) resetPreview()
    await load(docs.value.length === 1 && page.value > 0 ? page.value - 1 : page.value)
  } catch (e) {
    error.value = String(e)
  }
}

onMounted(load)
</script>

<template>
  <div class="page-intro">
    <p>按文件名查找文档，查看导入状态和切片。</p>
    <form class="actions library-search" @submit.prevent="search">
      <div class="field">
        <input id="document-filter" v-model="keyword" type="search" aria-label="文件名" placeholder="搜索文件名"/>
      </div>
      <button class="button primary" type="submit"><Search :size="15" aria-hidden="true"/>搜索</button>
      <button class="icon-button" type="button" aria-label="刷新文档" title="刷新文档" @click="load()">
        <RefreshCw :size="16" aria-hidden="true"/>
      </button>
    </form>
  </div>

  <div v-if="error" class="notice error" role="alert">{{ error }}</div>
  <LoadingBlock v-if="loading"/>
  <div v-else class="library-columns">
    <section class="panel document-list" aria-label="文档列表">
      <div v-if="docs.length" class="table-wrap" tabindex="0" role="region" aria-label="文档表格">
        <table class="data-table">
          <thead>
            <tr>
              <th scope="col">文档</th><th scope="col">类型</th><th scope="col">切片</th>
              <th scope="col">状态</th><th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="document in docs" :key="document.documentId" :class="{'is-selected': selected?.documentId === document.documentId}">
              <td><strong>{{ document.fileName }}</strong><small class="muted">{{ document.documentId }}</small></td>
              <td>{{ document.fileType || '—' }}</td>
              <td>{{ document.chunkCount || 0 }}</td>
              <td><StatusBadge :value="document.status || 'READY'"/></td>
              <td>
                <div class="document-actions">
                  <button class="icon-button" type="button" :aria-label="'查看切片：' + document.fileName"
                          title="查看切片" aria-controls="document-chunk-preview"
                          :aria-current="selected?.documentId === document.documentId ? 'true' : undefined" @click="inspect(document)">
                    <Eye :size="16" aria-hidden="true"/>
                  </button>
                  <button class="icon-button danger" type="button" :aria-label="'删除文档：' + document.fileName"
                          title="删除文档" @click="remove(document)">
                    <Trash2 :size="16" aria-hidden="true"/>
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <EmptyState v-else title="未找到文档" message="可以调整搜索条件，或先导入文档。"/>
      <PaginationControl :page="page" :size="pageSize" :total="total" @change="load"/>
    </section>

    <section id="document-chunk-preview" class="panel document-preview" aria-label="切片预览">
      <template v-if="selected">
        <header class="preview-heading">
          <h2 ref="previewHeading" tabindex="-1">切片预览</h2>
          <p>{{ selected.fileName }}</p>
        </header>
        <LoadingBlock v-if="chunkLoading"/>
        <div v-else-if="chunkError" class="notice error" role="alert">{{ chunkError }}</div>
        <div v-else-if="chunks.length" class="preview-scroll" tabindex="0" role="region" aria-label="切片内容">
          <div v-for="(chunk, index) in chunks" :key="index" class="result-item">
            <p>{{ chunk.content || chunk.text || JSON.stringify(chunk) }}</p>
          </div>
        </div>
        <EmptyState v-else title="此文档还没有切片"/>
        <PaginationControl :page="chunkPage" :size="pageSize" :total="chunkTotal" @change="loadChunks"/>
      </template>
      <EmptyState v-else title="选择文档查看切片" message="选中文档旁的查看按钮，核对切片原文。"/>
    </section>
  </div>
</template>

<style scoped>
.library-search { flex-wrap: nowrap; max-width: 100% }
.library-search .button { min-height: 40px; white-space: nowrap }
.library-columns { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--space-4); align-items: start }
.document-list { overflow: hidden }
.document-list .data-table small { display: block; margin-top: var(--space-1) }
.document-list .data-table td:nth-child(n + 2) { white-space: nowrap }
.document-actions { display: flex; align-items: center }
.document-preview { display: flex; flex-direction: column; overflow: hidden }
.preview-heading { padding: var(--space-4); border-bottom: 1px solid var(--line) }
.preview-heading h2 { margin: 0 0 var(--space-2); font-size: var(--font-heading) }
.preview-heading p { margin: 0; color: var(--secondary); font-size: var(--font-small) }
.preview-scroll { min-height: 0; overflow-y: auto; overscroll-behavior: contain }
.preview-scroll:focus-visible, .table-wrap:focus-visible { outline-offset: -3px }
.preview-scroll .result-item + .result-item { border-top: 1px solid var(--line) }
.preview-scroll .result-item p { margin: 0; color: var(--text); white-space: pre-wrap }
.document-preview .notice { margin: var(--space-4) }
@media (min-width: 1200px) {
  .library-columns { grid-template-columns: minmax(0, 1.45fr) minmax(0, 1fr) }
  .document-preview {
    max-height: clamp(300px, calc(100dvh - var(--toolbar-height) - var(--space-6) - 40px - 2 * var(--space-5)), 975.33px);
  }
  .preview-heading, .document-preview .pagination { flex: none }
}
</style>
