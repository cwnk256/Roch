<script lang="ts" setup>
import {computed, ref} from 'vue'
import {Search} from 'lucide-vue-next'
import {api} from '../api'
import {normalizeSearchResults} from '../lib/fileSearch'
import {paginateItems} from '../lib/pagination'
import type {SearchResponse} from '../types'
import EmptyState from '../components/EmptyState.vue'
import LoadingBlock from '../components/LoadingBlock.vue'
import PaginationControl from '../components/PaginationControl.vue'

const query = ref(''), topK = ref(8), loading = ref(false), error = ref(''), response = ref<SearchResponse | null>(null)
const pageSize = 10
const resultPage = ref(0)
const rows = computed(() => normalizeSearchResults(response.value))
const visibleRows = computed(() => paginateItems(rows.value, pageSize, resultPage.value))

async function search() {
  if (!query.value.trim()) return;
  loading.value = true;
  error.value = '';
  try {
    response.value = await api.search({query: query.value, topK: topK.value, threshold: .35, documentIds: []})
    resultPage.value = 0
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}
</script>
<template>
  <div class="page-intro"><p>用自然语言搜索知识库，并查看文档中的命中片段。</p></div>
  <div class="flow">
    <form class="panel panel-pad search-form" @submit.prevent="search">
      <div class="field search-query">
        <label for="search-query">搜索问题</label>
        <input id="search-query" v-model="query" type="search" placeholder="例如，年度报告公布了哪些目标？"/>
      </div>
      <div class="field search-limit">
        <label for="search-limit">检索数量</label>
        <input id="search-limit" v-model.number="topK" max="20" min="1" type="number"/>
      </div>
      <div class="form-action">
        <button class="button primary" type="submit" :disabled="loading || !query.trim()">
          <Search :size="16" aria-hidden="true"/>搜索
        </button>
      </div>
    </form>

    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <LoadingBlock v-if="loading"/>
    <section v-else-if="response" class="panel search-results" aria-labelledby="search-results-heading">
      <header class="section-heading">
        <h2 id="search-results-heading">搜索结果</h2>
        <span>{{ rows.length }} 份文档</span>
      </header>
      <article v-for="(row, index) in visibleRows" :key="row.documentId || index" class="search-result">
        <div class="section-heading">
          <h3>{{ row.fileName || row.documentId || '结果 ' + (resultPage * pageSize + index + 1) }}</h3>
          <span>{{ row.hitCount || row.chunks.length }} 处命中 · {{ ((row.score || 0) * 100).toFixed(1) }}%</span>
        </div>
        <ol class="result-list">
          <li v-for="(chunk, chunkIndex) in row.chunks" :key="chunk.chunkId || chunkIndex" class="result-item">
            <small v-if="chunk.section" class="muted">{{ chunk.section }}</small>
            <p>{{ chunk.content }}</p>
          </li>
        </ol>
      </article>
      <EmptyState v-if="!rows.length" title="未找到匹配文档" message="换个问题或缩短关键词再试。"/>
      <PaginationControl :page="resultPage" :size="pageSize" :total="rows.length" @change="resultPage = $event"/>
    </section>
    <div v-else-if="!error" class="panel">
      <EmptyState title="输入问题以搜索知识库" message="命中的文档与切片会显示在这里。"/>
    </div>
  </div>
</template>

<style scoped>
.search-form { display: grid; grid-template-columns: minmax(0, 1fr) 92px auto; gap: var(--space-4) }
.search-form .button { min-height: 40px }
.search-results > header { margin: 0; padding: var(--space-4) var(--space-5) }
.search-result { border-top: 1px solid var(--line); padding: var(--space-4) var(--space-5) }
.search-result h3 { font-size: var(--font-body) }
.search-result .result-list { margin: 0; padding: 0; list-style: none }
.search-result .result-item { background: var(--inset); border-radius: var(--radius-control) }
.search-result .result-item p { color: var(--text); white-space: pre-wrap }
@media (max-width: 600px) {
  .search-form { grid-template-columns: minmax(0, 1fr) auto }
  .search-query { grid-column: 1 / -1 }
  .search-limit { max-width: 112px }
  .search-result, .search-results > header { padding-inline: var(--space-4) }
}
</style>
