<script lang="ts" setup>
import {computed, onMounted, ref} from 'vue'
import {ArrowRight, FileText, Plus, RefreshCw} from 'lucide-vue-next'
import {RouterLink} from 'vue-router'
import {api} from '../api'
import type {DocumentMeta, Task} from '../types'
import StatusBadge from '../components/StatusBadge.vue'
import LoadingBlock from '../components/LoadingBlock.vue'
import EmptyState from '../components/EmptyState.vue'
import PaginationControl from '../components/PaginationControl.vue'

const pageSize = 10
const docs = ref<DocumentMeta[]>([])
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
    <div><p class="eyebrow">OVERVIEW</p>
      <h2>知识工作台</h2>
      <p>系统总览</p></div>
    <div class="actions">
      <RouterLink class="button" to="/import">
        <Plus :size="15"/>
        导入文件
      </RouterLink>
      <button class="button" @click="load()">
        <RefreshCw :size="15"/>
        刷新
      </button>
    </div>
  </section>
  <div v-if="error" class="notice error">{{ error }}</div>
  <LoadingBlock v-if="loading"/>
  <template v-else>
    <div class="grid grid-4">
      <div class="panel metric"><span class="metric-label">Documents</span>
        <div class="metric-value">{{ docs.length }}</div>
        <span class="metric-note">Search</span></div>
      <div class="panel metric"><span class="metric-label">Jobs</span>
        <div class="metric-value">{{ taskTotal }}</div>
        <span class="metric-note">Recent records</span></div>
      <div class="panel metric"><span class="metric-label">Completed</span>
        <div class="metric-value">{{ completedCount }}</div>
        <span class="metric-note">On this page</span></div>
      <div class="panel metric"><span class="metric-label">In progress</span>
        <div class="metric-value">{{ inProgressCount }}</div>
        <span class="metric-note">On this page</span></div>
    </div>
    <div class="grid grid-3">
      <div class="span-2">
        <div class="section-heading"><h3>最近工作</h3>
          <RouterLink class="button" to="/reports">查看全部
            <ArrowRight :size="14"/>
          </RouterLink>
        </div>
        <div class="panel table-wrap">
          <table v-if="tasks.length" class="data-table">
            <thead>
            <tr>
              <th>Job</th>
              <th>Type</th>
              <th>Status</th>
              <th>Action</th>
            </tr>
            </thead>
            <tbody>
            <tr v-for="task in tasks" :key="task.taskId">
              <td>{{ task.title || task.taskId }}</td>
              <td>{{ task.type }}</td>
              <td>
                <StatusBadge :value="task.status"/>
              </td>
              <td>
                <RouterLink class="muted" to="/reports">Open</RouterLink>
              </td>
            </tr>
            </tbody>
          </table>
          <EmptyState v-else message="Create one from Reports" title="No jobs yet"/>
          <PaginationControl :page="taskPage" :size="pageSize" :total="taskTotal" @change="load"/>
        </div>
      </div>
      <div>
        <div class="section-heading"><h3>最近文档</h3>
          <RouterLink class="muted" to="/documents">查看全部</RouterLink>
        </div>
        <div v-if="docs.length" class="panel">
          <div v-for="doc in docs" :key="doc.documentId" class="result-item"><strong>
            <FileText :size="14" style="vertical-align:-2px"/>
            {{ doc.fileName }}</strong>
            <p>{{ doc.chunkCount || 0 }} chunks · {{ doc.fileType || 'Unknown' }}</p></div>
        </div>
        <div v-else class="panel">
          <EmptyState title="No documents"/>
        </div>
      </div>
    </div>
  </template>
</template>
