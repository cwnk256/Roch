<script lang="ts" setup>
import {ref} from 'vue'
import {Search} from 'lucide-vue-next'
import {api} from '../api'
import {normalizeSearchResults} from '../lib/fileSearch'
import type {SearchResponse} from '../types'
import EmptyState from '../components/EmptyState.vue'
import LoadingBlock from '../components/LoadingBlock.vue'

const query = ref(''), topK = ref(8), loading = ref(false), error = ref(''), response = ref<SearchResponse | null>(null)

async function search() {
  if (!query.value.trim()) return;
  loading.value = true;
  error.value = '';
  try {
    response.value = await api.search({query: query.value, topK: topK.value, threshold: .35, documentIds: []})
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

function rows() {
  return normalizeSearchResults(response.value)
}
</script>
<template>
  <section class="page-intro">
    <div>
      <p class="eyebrow">RETRIEVAL / SEARCH</p>
      <h2>文件检索</h2>
      <p>自然语言搜索你的知识库</p>
    </div>
  </section>
  <div class="panel" style="padding:18px;margin-bottom:18px">
    <form class="form-grid" @submit.prevent="search">
      <div class="field span-2">
        <label>Question</label>
        <input v-model="query" placeholder="你想问什么?"/>
      </div>
      <div class="field">
        <label>Top K</label>
        <input v-model.number="topK" max="20" min="1" type="number"/>
      </div>
      <div style="display:flex;align-items:end">
        <button class="button primary" type="submit">
          <Search :size="15"/>
          搜索
        </button>
      </div>
    </form>
  </div>
  <div v-if="error" class="notice error">
    {{ error }}
  </div>
  <LoadingBlock v-if="loading"/>
  <div v-else-if="response" class="panel" style="padding:18px">
    <div class="section-heading">
      <h3>Results</h3>
      <span>{{ rows().length }} items</span></div>
    <div v-if="rows().length" class="result-list">
      <article v-for="(row, i) in rows()" :key="row.documentId || i" class="result-item">
        <div class="section-heading">
          <strong>
            {{ row.fileName || row.documentId || `Result ${i + 1}` }}
          </strong>
          <span>
          {{ row.hitCount || row.chunks.length }} hits · {{ ((row.score || 0) * 100).toFixed(1) }}%
        </span>
        </div>
        <div v-for="(chunk, chunkIndex) in row.chunks" :key="chunk.chunkId || chunkIndex">
          <small v-if="chunk.section" class="muted">
            {{ chunk.section }}</small>
          <p>{{ chunk.content }}
          </p>
        </div>
      </article>
    </div>
    <EmptyState v-else message="Try another question" title="No results"/>
  </div>
  <div v-else class="panel">
    <EmptyState message="Results will appear here" title="Ask a question to search"/>
  </div>
</template>

