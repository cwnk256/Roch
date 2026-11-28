<script lang="ts" setup>
import {computed, onMounted, ref} from 'vue'
import {ArrowRight, FileText, Plus, RefreshCw} from 'lucide-vue-next'
import {RouterLink} from 'vue-router'
import {api} from '../api'
import {taskTypeLabels} from '../lib/report'
import type {DocumentMeta, Task} from '../types'
import StatusBadge from '../components/StatusBadge.vue'
import LoadingBlock from '../components/LoadingBlock.vue'
import EmptyState from '../components/EmptyState.vue'
import PaginationControl from '../components/PaginationControl.vue'

const pageSize = 10
const docs = ref<DocumentMeta[]>([])
const documentTotal = ref(0)
const tasks = ref<Task[]>([])
const taskPage = ref(0)
const taskTotal = ref(0)
const loading = ref(true)
const error = ref('')
const completedCount = computed(() => tasks.value.filter((task) => task.status === 'COMPLETED').length)
const inProgressCount = computed(() => tasks.value.filter((task) => ['PENDING', 'RUNNING'].includes(task.status)).length)

async function load(page = taskPage.value) {
  loading.value = true
  error.value = ''
  try {
    const [documents, taskResult] = await Promise.all([
      api.listDocuments({page: 0, size: 6}),
      api.listTasks({page, size: pageSize})
    ])
    docs.value = documents.content || []
    documentTotal.value = documents.total
    tasks.value = taskResult.content || []
    taskPage.value = taskResult.page
    taskTotal.value = taskResult.total
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="page-intro">
    <div>
      <h2>知识工作台</h2>
      <p>查看最近的文档和报告任务。</p>
    </div>
    <div class="actions">
      <button class="button" type="button" @click="load()"><RefreshCw :size="15" aria-hidden="true"/>刷新</button>
      <RouterLink class="button primary" to="/import"><Plus :size="16" aria-hidden="true"/>导入文档</RouterLink>
    </div>
  </section>

  <div v-if="error" class="notice error" role="alert">{{ error }}</div>
  <LoadingBlock v-if="loading"/>
  <template v-else>
    <dl class="panel overview-metrics" aria-label="工作台概览">
      <div><dt>知识库文档</dt><dd>{{ documentTotal }}</dd></div>
      <div><dt>报告任务</dt><dd>{{ taskTotal }}</dd></div>
      <div><dt>本页已完成</dt><dd>{{ completedCount }}</dd></div>
      <div><dt>本页待处理或进行中</dt><dd>{{ inProgressCount }}</dd></div>
    </dl>

    <div class="dashboard-columns">
      <section class="panel dashboard-panel">
        <header class="section-heading dashboard-heading">
          <h3>最近工作</h3>
          <RouterLink class="dashboard-link" to="/reports">全部任务<ArrowRight :size="14" aria-hidden="true"/></RouterLink>
        </header>
        <div v-if="tasks.length" class="table-wrap" tabindex="0" role="region" aria-label="最近工作列表">
          <table class="data-table">
            <thead><tr><th scope="col">任务</th><th scope="col">类型</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
            <tbody>
              <tr v-for="task in tasks" :key="task.taskId">
                <td>{{ task.title || task.taskId }}</td>
                <td>{{ taskTypeLabels[task.type] || task.type }}</td>
                <td><StatusBadge :value="task.status"/></td>
                <td><RouterLink :aria-label="'查看任务列表，任务：' + (task.title || task.taskId)" to="/reports">查看</RouterLink></td>
              </tr>
            </tbody>
          </table>
        </div>
        <EmptyState v-else title="还没有报告任务" message="在总结 / 报告中创建任务。"/>
        <PaginationControl :page="taskPage" :size="pageSize" :total="taskTotal" @change="load"/>
      </section>

      <section class="panel dashboard-panel">
        <header class="section-heading dashboard-heading">
          <h3>最近文档</h3>
          <RouterLink class="dashboard-link" to="/documents">全部文档<ArrowRight :size="14" aria-hidden="true"/></RouterLink>
        </header>
        <ul v-if="docs.length" class="recent-documents">
          <li v-for="doc in docs" :key="doc.documentId">
            <FileText :size="18" aria-hidden="true"/>
            <div class="result-item">
              <strong>{{ doc.fileName }}</strong>
              <p>{{ doc.fileType || '未知类型' }} · {{ doc.chunkCount || 0 }} 个切片</p>
            </div>
          </li>
        </ul>
        <EmptyState v-else title="还没有文档" message="导入文档后，就能在这里查看。"/>
      </section>
    </div>
  </template>
</template>

<style scoped>
.overview-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); margin: 0 0 var(--space-5); overflow: hidden }
.overview-metrics > div { display: grid; align-content: start; gap: var(--space-2); padding: var(--space-4) var(--space-5); border-inline-end: 1px solid var(--line) }
.overview-metrics > div:last-child { border-inline-end: 0 }
.overview-metrics dt { color: var(--secondary); font-size: var(--font-small) }
.overview-metrics dd { margin: 0; color: var(--text); font-size: 1.625rem; font-weight: 650; font-variant-numeric: tabular-nums }
.dashboard-columns { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(0, 1fr); align-items: start; gap: var(--space-4) }
.dashboard-panel { overflow: hidden }
.dashboard-heading { margin: 0; padding: var(--space-4); border-bottom: 1px solid var(--line) }
.dashboard-link { display: inline-flex; align-items: center; gap: var(--space-1); font-size: var(--font-small); text-decoration: none }
.dashboard-link:hover { text-decoration: underline }
.recent-documents { list-style: none; padding: 0; margin: 0 }
.recent-documents li { display: flex; align-items: start; gap: var(--space-3); padding: var(--space-3) var(--space-4) }
.recent-documents li + li { border-top: 1px solid var(--line) }
.recent-documents svg { flex: none; color: var(--accent); margin-top: 2px }
.recent-documents .result-item { padding: 0 }
.recent-documents strong { font-size: var(--font-small) }
@media (max-width: 1180px) {
  .dashboard-columns { grid-template-columns: minmax(0, 1fr) }
}
@media (max-width: 600px) {
  .overview-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)) }
  .overview-metrics > div { padding: var(--space-3) var(--space-4) }
  .overview-metrics > div:nth-child(even) { border-inline-end: 0 }
  .overview-metrics > div:nth-child(n + 3) { border-top: 1px solid var(--line) }
}
</style>
