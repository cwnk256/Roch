<script lang="ts" setup>
import {computed, onMounted, onUnmounted, ref, watch} from 'vue';
import {ChevronDown, Download, FileOutput, Play, RefreshCw, RotateCcw} from 'lucide-vue-next';
import {api} from '../api';
import type {Artifact, DocumentMeta, Task} from '../types';
import StatusBadge from '../components/StatusBadge.vue';
import LoadingBlock from '../components/LoadingBlock.vue';
import EmptyState from '../components/EmptyState.vue';
import PaginationControl from '../components/PaginationControl.vue';
import {paginateItems} from '../lib/pagination';
import {getTaskDownloadUrl} from '../lib/task-download';

const docs = ref<DocumentMeta[]>([]), tasks = ref<Task[]>([]), selectedIds = ref<string[]>([]),
    type = ref<'SUMMARY' | 'REPORT'>('SUMMARY'), instruction = ref(''), selectedTask = ref<Task | null>(null),
    result = ref<unknown>(null), artifacts = ref<Artifact[]>([]), loading = ref(false), error = ref(''),
    documentPage = ref(0), resultError = ref('');

function errText(e: unknown): string {
  const anyE = e as { response?: { data?: { error?: string } } };
  return anyE?.response?.data?.error || String(e);
}
const codeCollapsed = ref(false);
const toggleCode = () => {codeCollapsed.value = !codeCollapsed.value};
const documentPageSize = 10;
const visibleDocuments = computed(() => paginateItems(docs.value, documentPageSize, documentPage.value));
watch(docs, documents => {
  const pageCount = Math.ceil(documents.length / documentPageSize);
  if (documentPage.value >= pageCount) documentPage.value = Math.max(pageCount - 1, 0)
});
const taskPage = ref(0);
const taskPageSize = 10;
const visibleTasks = computed(() => paginateItems(tasks.value, taskPageSize, taskPage.value));
watch(tasks, ts => {
  const pageCount = Math.ceil(ts.length / taskPageSize);
  if (taskPage.value >= pageCount) taskPage.value = Math.max(pageCount - 1, 0)
});

async function load() {
  try {
    const [d, t] = await Promise.all([api.listDocuments({page: 0, size: 100}), api.listTasks({page: 0, size: 50})]);
    docs.value = d.content || [];
    tasks.value = t.content || []
  } catch (e) {
    error.value = String(e)
  }
}

async function create() {
  if (!selectedIds.value.length || !instruction.value.trim()) return;
  loading.value = true;
  try {
    selectedTask.value = await api.createTask({
      type: type.value,
      documentIds: selectedIds.value,
      instruction: instruction.value
    });
    tasks.value = [selectedTask.value, ...tasks.value];
    poll(selectedTask.value.taskId)
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

async function poll(id: string) {
  stopPoll();
  const tick = async () => {
    try {
      const t = await api.task(id);
      selectedTask.value = t;
      if (t.status === 'PENDING' || t.status === 'RUNNING') {
        pollTimer = setTimeout(tick, 1800) as unknown as number;
      } else if (t.status === 'COMPLETED') {
        resultError.value = '';
        try {
          result.value = await api.result(id);
          artifacts.value = await api.artifacts(id);
        } catch (e2) {
          result.value = null;
          resultError.value = errText(e2)
        }
        await load()
      }
    } catch (e) {
      error.value = String(e)
    }
  };
  tick()
}

let pollTimer: number | null = null;
function stopPoll() {
  if (pollTimer !== null) {
    clearTimeout(pollTimer);
    pollTimer = null
  }
}

async function open(t: Task) {
  selectedTask.value = t;
  result.value = null;
  artifacts.value = [];
  resultError.value = '';
  if (t.status === 'COMPLETED') {
    try {
      result.value = await api.result(t.taskId);
      artifacts.value = await api.artifacts(t.taskId)
    } catch (e) {
      resultError.value = errText(e)
    }
  } else if (t.status === 'PENDING' || t.status === 'RUNNING') {
    poll(t.taskId)
  }
}

async function retry() {
  if (selectedTask.value) {
    selectedTask.value = await api.retry(selectedTask.value.taskId);
    poll(selectedTask.value.taskId)
  }
}

async function reExport() {
  if (selectedTask.value) {
    const x = await api.reExport(selectedTask.value.taskId);
    artifacts.value = [...artifacts.value, x]
  }
}

async function downloadTask(t: Task) {
  try {
    let url = getTaskDownloadUrl(t, []);
    if (!url) {
      const taskArtifacts = await api.artifacts(t.taskId);
      url = getTaskDownloadUrl(t, taskArtifacts)
    }
    if (!url) throw new Error('该任务没有可下载的历史文件');
    window.open(url, '_blank', 'noopener')
  } catch (e) {
    error.value = String(e)
  }
}

onMounted(load)
onUnmounted(stopPoll)
</script>
<template>
  <section class="page-intro">
    <div><p class="eyebrow">GENERATION / REPORTS</p>
      <h2>总结 / 报告</h2>
      <p>选择知识文档，生成结构化总结并导出 PDF。</p></div>
    <button class="button" @click="load">
      <RefreshCw :size="15"/>
      刷新任务
    </button>
  </section>
  <div v-if="error" class="notice error">{{ error }}</div>
  <div class="grid" style="grid-template-columns:minmax(0,.75fr) minmax(0,1.25fr);align-items:start">
    <div class="panel" style="padding:20px"><h3 style="margin-top:0">创建生成任务</h3>
      <div class="field"><label>任务类型</label><select v-model="type">
        <option value="SUMMARY">SUMMARY · 总结</option>
        <option value="REPORT">REPORT · 报告</option>
      </select></div>
      <div class="field" style="margin-top:14px"><label>选择文档</label><label v-for="d in visibleDocuments"
                                                                               :key="d.documentId" class="notice"
                                                                               style="display:flex;gap:9px;align-items:center;margin-top:6px"><input
          v-model="selectedIds" :value="d.documentId" type="checkbox"/> {{ d.fileName }}</label>
        <PaginationControl :page="documentPage" :size="documentPageSize" :total="docs.length"
                           @change="documentPage=$event"/>
      </div>
      <div class="field" style="margin-top:14px"><label>生成指令</label><textarea v-model="instruction"
                                                                                  placeholder="例如：提炼维护周期、安全注意事项和执行要点。"></textarea>
      </div>
      <button :disabled="loading||!selectedIds.length" class="button primary" style="margin-top:14px" @click="create">
        <Play :size="15"/>
        {{ loading ? '提交中…' : '创建任务' }}
      </button>
    </div>
    <div class="panel" style="padding:20px">
      <div class="section-heading"><h3>任务历史</h3><span>{{ tasks.length }} 条</span></div>
      <div v-if="tasks.length" class="result-list">
        <div v-for="t in visibleTasks" :key="t.taskId" class="result-item task-history-item">
          <button class="task-history-main" type="button" @click="open(t)"><strong>{{ t.title || t.taskId }}</strong>
            <p>
              <StatusBadge :value="t.status"/>
              {{ t.type }} · {{ t.taskId }}
            </p>
          </button>
          <button v-if="t.status==='COMPLETED'" aria-label="下载历史文件" class="icon-button" title="下载历史文件"
                  type="button" @click.stop="downloadTask(t)">
            <Download :size="16"/>
          </button>
        </div>
        <PaginationControl :page="taskPage" :size="taskPageSize" :total="tasks.length"
                           @change="taskPage=$event"/>
      </div>
      <EmptyState v-else title="还没有报告任务"/>
    </div>
  </div>
  <div v-if="selectedTask" class="panel" style="padding:20px;margin-top:18px">
    <div class="section-heading">
      <div><h3>{{ selectedTask.title || selectedTask.taskId }}</h3>
        <p class="muted">{{ selectedTask.currentStep || selectedTask.status }}{{ selectedTask.progress}}</p>
      </div>
      <div class="actions">
        <StatusBadge :value="selectedTask.status"/>
        <button v-if="selectedTask.status==='FAILED'" class="button" @click="retry">
          <RotateCcw :size="15"/>
          重试
        </button>
        <button v-if="selectedTask.status==='COMPLETED'" class="button" @click="reExport">
          <FileOutput :size="15"/>
          重新导出
        </button>
      </div>
    </div>
    <LoadingBlock v-if="selectedTask.status==='PENDING'||selectedTask.status==='RUNNING'"/>
    <template v-else-if="result">
      <div class="section-heading collapsible-heading"  @click="toggleCode"><h3>结构化结果</h3><ChevronDown :class="['chevron', {collapsed: codeCollapsed}]" :size="16"/></div>
      <pre v-show="!codeCollapsed" class="code-view">{{ JSON.stringify(result, null, 2) }}</pre>
      <div class="section-heading"><h3>Artifacts</h3></div>
      <div v-if="artifacts.length" class="result-list">
        <div v-for="a in artifacts" :key="a.artifactId" class="result-item"><strong>{{ a.fileName }}</strong>
          <p>{{ a.contentType }} · {{ a.fileSize }} bytes <a :href="a.downloadUrl" target="_blank">
            <Download :size="14" style="vertical-align:-2px"/>
            下载</a></p>
        </div>
      </div>
      <EmptyState v-else title="暂无输出文件"/>
    </template>
    <div v-else class="notice error">{{ resultError || selectedTask.errorMessage || '任务没有结果' }}</div>
  </div>
</template>

<style>
.section-heading
{
  
}
.collapsible-heading {
  cursor: pointer;
  user-select: none;
}
.collapsible-heading .chevron {
  transition: transform .15s ease;
}
.collapsible-heading .chevron.collapsed {
  transform: rotate(-90deg);
}
</style>