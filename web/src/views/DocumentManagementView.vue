<script lang="ts" setup>
import {onMounted, ref} from 'vue'
import {Eye, RefreshCw, Search, Trash2} from 'lucide-vue-next'
import {api} from '../api'
import type {DocumentMeta} from '../types'
import EmptyState from '../components/EmptyState.vue'
import LoadingBlock from '../components/LoadingBlock.vue'
import PaginationControl from '../components/PaginationControl.vue'

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
}

function inspect(document: DocumentMeta) {
  resetPreview(document)
  void loadChunks(0)
}

async function loadChunks(nextPage: number): Promise<void> {
  const documentId = selected.value?.documentId
  if (!documentId) return
  const request = ++chunkRequest
  error.value = ''
  try {
    const result = await api.chunks(documentId, {page: nextPage, size: pageSize})
    if (request !== chunkRequest) return
    const lastPage = Math.max(0, Math.ceil(result.total / pageSize) - 1)
    if (nextPage > lastPage) return loadChunks(lastPage)
    chunks.value = result.content || []
    chunkPage.value = result.page
    chunkTotal.value = result.total
  } catch (e) {
    if (request === chunkRequest) error.value = String(e)
  }
}

async function remove(document: DocumentMeta) {
  if (!confirm(`Delete ${document.fileName}?`)) return
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
  <section class="page-intro">
    <div><p class="eyebrow">LIBRARY / DOCUMENTS</p>
      <h2>文档管理</h2>
      <p>浏览文档，切片和导入状态。</p></div>
    <div class="actions">
      <div class="field"><input v-model="keyword" placeholder="Search file name" @keyup.enter="search"/></div>
      <button class="button primary" @click="search">
        <Search :size="15"/>
        搜索
      </button>
      <button class="button" @click="load()">
        <RefreshCw :size="15"/>
        刷新
      </button>
    </div>
  </section>
  <div v-if="error" class="notice error">{{ error }}</div>
  <LoadingBlock v-if="loading"/>
  <div v-else class="grid" style="grid-template-columns:minmax(0,1.4fr) minmax(260px,.6fr);align-items:start">
    <div class="panel table-wrap">
      <table v-if="docs.length" class="data-table">
        <thead>
        <tr>
          <th>File</th>
          <th>Type</th>
          <th>Chunks</th>
          <th>Status</th>
          <th></th>
        </tr>
        </thead>
        <tbody>
        <tr v-for="document in docs" :key="document.documentId">
          <td><strong>{{ document.fileName }}</strong><br/><small class="muted">{{ document.documentId }}</small></td>
          <td>{{ document.fileType || '-' }}</td>
          <td>{{ document.chunkCount || 0 }}</td>
          <td>{{ document.status || 'READY' }}</td>
          <td>
            <button class="icon-button" title="View chunks" @click="inspect(document)">
              <Eye :size="16"/>
            </button>
            <button class="icon-button" title="Delete document" @click="remove(document)">
              <Trash2 :size="16"/>
            </button>
          </td>
        </tr>
        </tbody>
      </table>
      <EmptyState v-else message="Import a document first" title="No documents"/>
      <PaginationControl :page="page" :size="pageSize" :total="total" @change="load"/>
    </div>
    <div class="panel" style="padding:18px">
      <template v-if="selected"><h3 style="margin-top:0">{{ selected.fileName }}</h3>
        <p class="muted" style="font-size:12px">{{ selected.documentId }}</p>
        <div class="section-heading"><h3>Chunk preview</h3><span>{{ chunkTotal }} items</span></div>
        <div class="result-list">
          <div v-for="(chunk, index) in chunks" :key="index" class="result-item"><p>
            {{ chunk.content || chunk.text || JSON.stringify(chunk) }}</p></div>
        </div>
        <PaginationControl :page="chunkPage" :size="pageSize" :total="chunkTotal" @change="loadChunks"/>
      </template>
      <EmptyState v-else message="Chunk content appears here" title="Select a document"/>
    </div>
  </div>
</template>
