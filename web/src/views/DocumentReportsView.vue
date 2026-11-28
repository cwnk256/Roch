<script lang="ts" setup>
import {computed, onMounted, onUnmounted, ref, watch} from 'vue';
import {ArrowDown, ArrowUp, Check, ChevronLeft, ChevronRight, Download, FileOutput, FileText, Play, RefreshCw, RotateCcw} from 'lucide-vue-next';
import {api} from '../api';
import type {Artifact, DocumentMeta, Task} from '../types';
import StatusBadge from '../components/StatusBadge.vue';
import LoadingBlock from '../components/LoadingBlock.vue';
import EmptyState from '../components/EmptyState.vue';
import PaginationControl from '../components/PaginationControl.vue';
import {paginateItems} from '../lib/pagination';
import ReportReader from '../components/ReportReader.vue';
import ReportExportOptions from '../components/ReportExportOptions.vue';
import {defaultExport, taskTypeLabels, type Report, type ReportTemplate, type TemplateSection, type TaskType} from '../lib/report';
import {getTaskDownloadUrl} from '../lib/task-download';

const docs = ref<DocumentMeta[]>([]);
const tasks = ref<Task[]>([]);
const selectedIds = ref<string[]>([]);
const type = ref<TaskType>('SUMMARY');
const instruction = ref('');
const selectedTask = ref<Task | null>(null);
const reportHeading = ref<HTMLHeadingElement | null>(null);
const result = ref<Report | null>(null);
const artifacts = ref<Artifact[]>([]);
const loading = ref(false);
const error = ref('');
const resultError = ref('');

function errText(e: unknown): string {
  const anyE = e as { response?: { data?: { error?: string } } };
  return anyE?.response?.data?.error || String(e);
}
const templates = ref<ReportTemplate[]>([]);
const templateId = ref('');
const availableTemplates = computed(() => templates.value.filter(template => template.type === type.value));
const chosenTemplate = computed(() => availableTemplates.value.find(template => template.templateId === templateId.value));
const sectionChoices = ref<(TemplateSection & {enabled: boolean; extraInstruction: string})[]>([]);
const sectionOrder = ref<string[]>([]);
const movedSectionId = ref('');
const reorderFeedback = ref('');
const selectedSections = computed(() => {
  const enabledSections = new Map(sectionChoices.value.filter(section => section.enabled)
      .map(section => [section.sectionId, section]));
  return sectionOrder.value.flatMap(sectionId => {
    const section = enabledSections.get(sectionId);
    return section ? [section] : [];
  });
});
const exportOptions = ref(defaultExport());
const reExportOptions = ref(defaultExport());
const dimensions = ref('');
const exporting = ref(false);
const activeView = ref<'create' | 'history' | 'detail'>('create');
watch([activeView, () => selectedTask.value?.taskId], ([view]) => {
  if (view === 'detail') reportHeading.value?.focus()
}, {flush: 'post'});
const creationStep = ref(0);
const detailView = ref<'report' | 'exports'>('report');
const artifactPage = ref(0);
const artifactPageSize = 10;
const visibleArtifacts = computed(() => paginateItems(artifacts.value, artifactPageSize, artifactPage.value));
const stepTitles = ['选择资料', '生成要求', '导出设置'];
const selectedDimensions = computed(() => dimensions.value.split(/[，,、\n]/).map(value => value.trim()).filter(Boolean));
const comparisonError = computed(() => {
  if (type.value !== 'COMPARE') return '';
  const values = selectedDimensions.value;
  if (selectedIds.value.length > 6 || values.length > 6 || selectedIds.value.length * values.length > 24)
    return '最多比较 6 份文档、6 个维度，文档数与维度数乘积不得超过 24。';
  if (new Set(values).size !== values.length || values.some(value => value.length > 120))
    return '比较维度不能重复，每项最多 120 字。';
  return '';
});
const canCreate = computed(() => instruction.value.trim() && !comparisonError.value &&
    selectedIds.value.length >= (type.value === 'COMPARE' ? 2 : 1));
watch(availableTemplates, choices => { templateId.value = choices[0]?.templateId || '' });
watch(chosenTemplate, template => {
  sectionOrder.value = [];
  sectionChoices.value = (template?.sections || []).map(section => ({...section, enabled: true, extraInstruction: ''}))
});
watch(() => sectionChoices.value.filter(section => section.enabled).map(section => section.sectionId), enabledIds => {
  movedSectionId.value = '';
  reorderFeedback.value = '';
  const retainedIds = sectionOrder.value.filter(sectionId => enabledIds.includes(sectionId));
  sectionOrder.value = [...retainedIds, ...enabledIds.filter(sectionId => !retainedIds.includes(sectionId))];
});
function moveSelectedSection(sectionId: string, delta: -1 | 1) {
  const index = selectedSections.value.findIndex(section => section.sectionId === sectionId);
  const destination = index + delta;
  if (index < 0 || destination < 0 || destination >= selectedSections.value.length) return;
  const orderedIds = selectedSections.value.map(section => section.sectionId);
  const [movedId] = orderedIds.splice(index, 1);
  orderedIds.splice(destination, 0, movedId);
  sectionOrder.value = orderedIds;
  movedSectionId.value = sectionId;
  reorderFeedback.value = `“${selectedSections.value[destination].title}”已${delta < 0 ? '上移' : '下移'}至第 ${destination + 1} 章`;
}
const documentPage = ref(0);
const documentTotal = ref(0);
const documentPageSize = 10;
let documentRequest = 0;
async function loadDocuments(page = documentPage.value): Promise<void> {
  const request = ++documentRequest;
  try {
    const response = await api.listDocuments({page, size: documentPageSize});
    if (request !== documentRequest || disposed) return;
    const lastPage = Math.max(0, Math.ceil(response.total / documentPageSize) - 1);
    if (page > lastPage) return loadDocuments(lastPage);
    docs.value = response.content;
    documentPage.value = response.page;
    documentTotal.value = response.total
  } catch (e) {
    if (request === documentRequest) error.value = errText(e)
  }
}
const taskPage = ref(0);
const taskPageSize = 10;
const taskTotal = ref(0);
let taskRequest = 0;
async function loadTasks(page = taskPage.value): Promise<void> {
  const request = ++taskRequest;
  try {
    const response = await api.listTasks({page, size: taskPageSize});
    if (request !== taskRequest || disposed) return;
    const lastPage = Math.max(0, Math.ceil(response.total / taskPageSize) - 1);
    if (page > lastPage) return loadTasks(lastPage);
    tasks.value = response.content;
    taskPage.value = response.page;
    taskTotal.value = response.total
  } catch (e) {
    if (request === taskRequest && !disposed) error.value = errText(e)
  }
}
watch(activeView, view => {
  if (view === 'history') void loadTasks()
});

function load() {
  error.value = '';
  void loadDocuments();
  if (!templates.value.length) {
    void api.templates().then(data => { templates.value = data }).catch(e => { error.value = errText(e) })
  }
  if (activeView.value === 'history') void loadTasks()
}

async function create() {
  if (!canCreate.value || loading.value) return;
  error.value = '';
  loading.value = true;
  try {
    const created = await api.createTask({
      type: type.value,
      documentIds: selectedIds.value,
      instruction: instruction.value,
      ...(chosenTemplate.value && {
        templateId: chosenTemplate.value.templateId,
        templateVersion: chosenTemplate.value.templateVersion,
        sections: selectedSections.value
            .map(section => ({sectionId: section.sectionId, instruction: section.extraInstruction}))
      }),
      ...(type.value === 'COMPARE' && selectedDimensions.value.length && {comparisonDimensions: selectedDimensions.value}),
      exportOptions: exportOptions.value
    });
    if (!disposed) open(created)
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

let generation = 0;
let disposed = false;
let stream: EventSource | null = null;
let pollTimer: number | undefined;

function stopWatching() {
  generation++;
  stream?.close();
  stream = null;
  window.clearTimeout(pollTimer)
}

function open(task: Task) {
  stopWatching();
  activeView.value = 'detail';
  detailView.value = 'report';
  artifactPage.value = 0;
  const active = generation;
  const id = task.taskId;
  result.value = null;
  artifacts.value = [];
  resultError.value = '';

  const apply = (snapshot: Task) => {
    if (generation !== active || snapshot.taskId !== id) return false;
    selectedTask.value = snapshot;
    const index = tasks.value.findIndex(item => item.taskId === id);
    if (index >= 0) tasks.value[index] = snapshot;
    if (snapshot.status === 'PENDING' || snapshot.status === 'RUNNING') return true;
    stream?.close();
    stream = null;
    if (snapshot.status === 'COMPLETED') {
      Promise.all([api.result(id), api.artifacts(id)]).then(([document, files]) => {
        if (generation !== active) return;
        result.value = document;
        reExportOptions.value = {...defaultExport(), ...document.configuration?.exportOptions};
        artifacts.value = files
      }).catch(e => { if (generation === active) resultError.value = errText(e) })
    }
    return false
  };

  if (!apply(task)) return;
  const poll = async () => {
    if (generation !== active) return;
    try {
      if (!apply(await api.task(id))) return
    } catch (e) {
      if (generation !== active) return;
      error.value = errText(e)
    }
    pollTimer = window.setTimeout(poll, 1800)
  };
  if (typeof EventSource === 'undefined') { void poll(); return }
  const source = new EventSource('/api/document-tasks/' + encodeURIComponent(id) + '/events');
  stream = source;
  const fallback = () => {
    if (stream !== source) return;
    source.close();
    stream = null;
    void poll()
  };
  source.addEventListener('progress', event => {
    if (stream !== source) return;
    try { apply(JSON.parse(event.data) as Task) } catch { fallback() }
  });
  source.onerror = fallback
}

async function retry() {
  const active = generation;
  if (!selectedTask.value) return;
  try {
    const created = await api.retry(selectedTask.value.taskId);
    if (generation === active) open(created)
  } catch (e) { if (generation === active) error.value = errText(e) }
}

async function reExport() {
  if (!selectedTask.value || exporting.value) return;
  const active = generation;
  exporting.value = true;
  try {
    const file = await api.reExport(selectedTask.value.taskId, reExportOptions.value);
    if (generation === active) {
      artifacts.value = [...artifacts.value, file];
      artifactPage.value = Math.max(0, Math.ceil(artifacts.value.length / artifactPageSize) - 1)
    }
  } catch (e) {
    if (generation === active) error.value = errText(e)
  } finally { exporting.value = false }
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
onUnmounted(() => { disposed = true; stopWatching() })
</script>
<template>
  <div class="report-workspace">
    <div class="page-intro">
      <p>选择资料生成报告、对比文档，并核对结论的出处。</p>
      <button class="button" type="button" @click="load"><RefreshCw :size="15" aria-hidden="true"/>刷新任务</button>
    </div>
    <div v-if="error" class="notice error page-error" role="alert">{{ error }}<button class="icon-button" aria-label="关闭错误提示" @click="error=''">×</button></div>
    <nav class="segmented-control" aria-label="报告工作台">
      <button :class="{active:activeView==='create'}" :aria-current="activeView==='create'?'page':undefined" @click="activeView='create'">创建任务</button>
      <button :class="{active:activeView==='history'}" :aria-current="activeView==='history'?'page':undefined" @click="activeView='history'">任务历史</button>
      <button v-if="selectedTask" :class="{active:activeView==='detail'}" :aria-current="activeView==='detail'?'page':undefined" @click="activeView='detail'">报告详情</button>
    </nav>

    <section v-show="activeView==='create'" class="panel workspace-panel task-builder" aria-label="创建任务">
      <nav class="creation-steps" aria-label="创建任务步骤">
        <button v-for="(title,index) in stepTitles" :key="title" :class="{active:creationStep===index}" :aria-current="creationStep===index?'step':undefined" @click="creationStep=index">
          <span>{{ index+1 }}</span>{{ title }}
        </button>
      </nav>
      <div class="builder-scroll" :key="creationStep">
        <div v-if="creationStep===0" class="builder-fields">
      <div class="field"><label for="report-type">任务类型</label><select id="report-type" v-model="type">
        <option value="SUMMARY">总结</option>
        <option value="REPORT">分析报告</option>
        <option value="COMPARE">多文档对比（至少两份）</option>
      </select></div>
      <div class="field document-picker">
        <div class="picker-heading"><span>选择文档</span><span class="selection-count">已选 {{ selectedIds.length }} 份</span></div>
        <div class="document-options">
        <label v-for="document in docs" :key="document.documentId" class="document-option"
               :class="{'is-selected': selectedIds.includes(document.documentId), 'is-unavailable': !!document.status && document.status !== 'READY'}">
          <input v-model="selectedIds" :value="document.documentId" type="checkbox"
                 :disabled="!!document.status && document.status !== 'READY'"/>
          <FileText :size="15" aria-hidden="true"/>
          <span class="document-name">{{ document.fileName }}</span>
          <small v-if="document.status && document.status !== 'READY'">文档未就绪</small>
        </label>
        </div>
        <p v-if="!docs.length" class="muted picker-empty">暂无可选文档</p>
        <PaginationControl :page="documentPage" :size="documentPageSize" :total="documentTotal" @change="loadDocuments"/>
      </div>

        </div>
        <div v-else-if="creationStep===1" class="builder-fields">
      <div v-if="availableTemplates.length" class="field">
        <label for="report-template">报告模板</label>
        <select id="report-template" v-model="templateId">
          <option v-if="type !== 'COMPARE'" value="">自动大纲</option>
          <option v-for="template in availableTemplates" :key="template.templateId" :value="template.templateId">
            {{ template.title }}
          </option>
        </select>
      </div>
      <details v-if="chosenTemplate" class="chapters">
        <summary>选择章节<span>已选 {{ selectedSections.length }} 章</span><ChevronRight :size="14" class="disclosure-chevron" aria-hidden="true"/></summary>
        <div v-for="section in sectionChoices" :key="section.sectionId" class="chapter-row" :class="{'is-disabled': !section.enabled}">
          <div class="chapter-heading"><label class="chapter-choice">
            <input type="checkbox" v-model="section.enabled" :disabled="section.required">
            <span>{{ section.title }}</span><small v-if="section.required" class="required-label"><Check :size="11" aria-hidden="true"/>必选</small>
          </label></div>
          <div v-if="section.enabled" class="field chapter-instruction">
            <p v-if="section.instruction" class="chapter-default"><span>模板默认要求</span>{{ section.instruction }}</p>
            <label :for="'chapter-extra-' + section.sectionId">额外生成要求（可选）</label>
            <textarea :id="'chapter-extra-' + section.sectionId" v-model="section.extraInstruction"
                      :aria-describedby="'chapter-extra-hint-' + section.sectionId"
                      placeholder="例如：重点说明风险，并用表格列出关键数据。"
                      rows="2" maxlength="2000"></textarea>
            <p :id="'chapter-extra-hint-' + section.sectionId" class="chapter-hint">补充对本章的要求，留空时按模板默认要求生成。</p>
          </div>
        </div>
      </details>
      <section v-if="chosenTemplate" class="chapter-sequence" aria-labelledby="chapter-sequence-title">
        <div class="sequence-heading"><h4 id="chapter-sequence-title">章节顺序</h4><span>按此顺序生成报告</span></div>
        <p class="sequence-hint">仅显示已选章节，使用上移、下移调整顺序。</p>
        <p class="sequence-feedback" role="status" aria-live="polite" aria-atomic="true">{{ reorderFeedback }}</p>
        <TransitionGroup v-if="selectedSections.length" name="section-reorder" tag="ol" class="sequence-list">
          <li v-for="(section, index) in selectedSections" :key="section.sectionId" class="sequence-item"
              :class="{'is-moved': movedSectionId === section.sectionId}">
            <span class="sequence-position" aria-hidden="true">{{ index + 1 }}</span>
            <span class="sequence-title">{{ section.title }}</span>
            <div class="chapter-order">
              <button type="button" class="button" :disabled="index === 0" :aria-label="'上移' + section.title"
                      @click="moveSelectedSection(section.sectionId, -1)"><ArrowUp :size="14"/><span>上移</span></button>
              <button type="button" class="button" :disabled="index === selectedSections.length - 1" :aria-label="'下移' + section.title"
                      @click="moveSelectedSection(section.sectionId, 1)"><ArrowDown :size="14"/><span>下移</span></button>
            </div>
          </li>
        </TransitionGroup>
        <p v-else class="sequence-hint">先选择章节，再调整顺序。</p>
      </section>
      <div v-if="type === 'COMPARE'" class="field"><label for="comparison-dimensions">比较维度（用逗号分隔）</label>
        <input id="comparison-dimensions" v-model="dimensions" placeholder="如：维护周期、年度预算">
      </div>
      <p v-if="comparisonError" class="notice error">{{ comparisonError }}</p>

      <div class="field"><label for="report-instruction">生成指令</label><textarea id="report-instruction" v-model="instruction" maxlength="4000"
                                                                                  placeholder="例如：提炼维护周期、安全注意事项和执行要点。"></textarea>
      </div>

        </div>
        <div v-else class="builder-fields">
          <ReportExportOptions v-model="exportOptions"/>
          <p class="creation-summary">已选 {{ selectedIds.length }} 份文档 · {{ taskTypeLabels[type] }}</p>
          <p v-if="!canCreate" class="notice">{{ comparisonError || (!instruction.trim()?'请在“生成要求”中填写生成指令。':type==='COMPARE'?'请至少选择两份不同文档。':'请先选择资料文档。') }}</p>
        </div>
      </div>
      <footer class="panel-footer">
        <button class="button" :disabled="creationStep===0" @click="creationStep--"><ChevronLeft :size="15"/>上一步</button>
        <span>{{ creationStep+1 }} / {{ stepTitles.length }}</span>
        <button v-if="creationStep<2" class="button primary" @click="creationStep++">下一步<ChevronRight :size="15"/></button>
        <button v-else :disabled="loading||!canCreate" class="button primary create-task" @click="create"><Play :size="15"/>{{ loading?'提交中…':'创建任务' }}</button>
      </footer>
    </section>

    <section v-show="activeView==='history'" class="panel workspace-panel history-panel" aria-label="任务历史">
      <div class="section-heading panel-heading"><h2>任务历史</h2><span>{{ taskTotal }} 条</span></div>
      <div v-if="tasks.length" class="history-scroll"><div class="result-list">
        <div v-for="t in tasks" :key="t.taskId" class="result-item task-history-item" :class="{'is-active': selectedTask?.taskId === t.taskId}">
          <button class="task-history-main" type="button" @click="open(t)"><strong>{{ t.title || t.taskId }}</strong>
            <p>
              <StatusBadge :value="t.status"/>
              {{ taskTypeLabels[t.type] || t.type }} · {{ t.taskId }}
            </p>
          </button>
          <button v-if="t.status==='COMPLETED'" aria-label="下载历史文件" class="icon-button" title="下载历史文件"
                  type="button" @click.stop="downloadTask(t)">
            <Download :size="16"/>
          </button>
        </div>
        </div></div>
        <PaginationControl :page="taskPage" :size="taskPageSize" :total="taskTotal" @change="loadTasks"/>
      <EmptyState v-if="!tasks.length" title="还没有报告任务"/>
    </section>

    <section v-if="selectedTask" v-show="activeView==='detail'" class="panel workspace-panel report-output" aria-label="报告详情">
      <div class="section-heading output-heading">
        <div><h2 ref="reportHeading" tabindex="-1">{{ selectedTask.title || selectedTask.taskId }}</h2><p class="muted">{{ selectedTask.currentStep }} · {{ selectedTask.progress }}%</p></div>
        <div class="actions">
          <StatusBadge :value="selectedTask.status"/>
          <button v-if="selectedTask.status==='FAILED'" class="button" @click="retry"><RotateCcw :size="15"/>重试</button>
        </div>
      </div>
      <LoadingBlock v-if="selectedTask.status==='PENDING'||selectedTask.status==='RUNNING'"/>
      <template v-else-if="result">
        <nav class="segmented-control detail-tabs" aria-label="报告内容">
          <button :class="{active:detailView==='report'}" :aria-current="detailView==='report'?'page':undefined" @click="detailView='report'">阅读报告</button>
          <button :class="{active:detailView==='exports'}" :aria-current="detailView==='exports'?'page':undefined" @click="detailView='exports'">导出与下载</button>
        </nav>
        <ReportReader v-show="detailView==='report'" :report="result"/>
        <section v-show="detailView==='exports'" class="exports-view" aria-label="报告导出">
          <div class="exports-scroll">
            <ReportExportOptions v-model="reExportOptions"/>
            <div v-if="artifacts.length" class="result-list">
              <div v-for="artifact in visibleArtifacts" :key="artifact.artifactId" class="result-item artifact-item">
                <strong>{{ artifact.fileName }}</strong><p>{{ (artifact.fileSize/1024).toFixed(1) }} KB</p>
                <a :href="artifact.downloadUrl" target="_blank" rel="noopener" class="button"><Download :size="14"/>下载</a>
              </div>
            </div>
            <EmptyState v-else title="暂无输出文件"/>
          </div>
          <PaginationControl :page="artifactPage" :size="artifactPageSize" :total="artifacts.length" @change="artifactPage=$event"/>
          <footer class="panel-footer"><span>从保存的结果重新导出</span><button class="button primary" :disabled="exporting" @click="reExport"><FileOutput :size="15"/>{{ exporting?'导出中…':'重新导出' }}</button></footer>
        </section>
      </template>
      <LoadingBlock v-else-if="selectedTask.status==='COMPLETED' && !resultError"/>
      <div v-else class="notice error"><strong v-if="selectedTask.errorCode">{{ selectedTask.errorCode }} · </strong>{{ resultError || selectedTask.errorMessage || '任务没有结果' }}</div>
    </section>
  </div>
</template>

<style scoped>
.report-workspace { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; gap: var(--space-3) }
.report-workspace .page-intro { margin: 0; flex: none }
.segmented-control { display: flex; align-self: flex-start; flex-wrap: wrap; gap: var(--space-1); flex: none; max-width: 100%; padding: var(--space-1); border: 1px solid var(--line); border-radius: 13px; background: var(--inset) }
.segmented-control button { min-height: 36px; border: 1px solid transparent; border-radius: var(--radius-control); padding: 6px 12px; color: var(--secondary); background: transparent; font-size: var(--font-small); font-weight: 600; transition: background-color var(--motion-fast) ease, color var(--motion-fast) ease }
.segmented-control button:hover { color: var(--text) }
.segmented-control button.active { color: var(--accent); background: var(--surface); border-color: var(--line) }
.workspace-panel { flex: 1; min-height: 0; display: flex; flex-direction: column }
.creation-steps { display: flex; flex: none; gap: 8px; padding-bottom: 16px; border-bottom: 1px solid var(--inset) }
.creation-steps button { display: flex; gap: 8px; align-items: center; justify-content: center; flex: 1; background: var(--inset); border: 1px solid var(--line); border-radius: 7px; padding: 10px 6px; font-size: 12px; color: var(--secondary) }
.creation-steps button.active { background: var(--selection); border-color: var(--control-line); color: var(--accent) }
.creation-steps button span { display: grid; place-items: center; width: 20px; height: 20px; border-radius: 50%; background: var(--inset); font-size: 11px }
.creation-steps button.active span { background: var(--accent); color: var(--on-accent) }
.builder-scroll, .history-scroll, .exports-scroll { flex: 1; min-height: 0 }
@media (min-width: 901px) and (min-height: 650px) {
  .builder-scroll, .history-scroll, .exports-scroll { overflow: auto; overscroll-behavior: contain; scrollbar-width: thin }
}
.builder-scroll { padding: 18px 2px }
.builder-fields { max-width: 760px; margin: 0 auto; display: grid; gap: 18px }
.panel-footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding-top: 14px; border-top: 1px solid var(--inset); flex: none; font-size: 12px; color: var(--secondary) }
.creation-summary { color: var(--secondary); font-size: 12px; margin: 0 }
.page-error { display: flex; justify-content: space-between; flex: none; max-height: 80px; overflow: auto }
.history-scroll { padding-right: 4px }
.history-panel > .pagination { flex: none }
.exports-view { flex: 1; min-height: 0; display: flex; flex-direction: column }
.exports-scroll { padding: 18px 2px }
.exports-scroll > .result-list { margin-top: 16px }
.artifact-item { position: relative; padding-right: 90px; overflow-wrap: anywhere }
.artifact-item > a { position: absolute; right: 10px; top: 14px }
.detail-tabs { margin-top: 12px }

.task-builder, .history-panel, .report-output { padding: 22px; min-width: 0 }

.panel-heading { margin: 0 0 2px }
.history-panel > .panel-heading { margin-bottom: 18px }
.picker-heading { display: flex; align-items: center; justify-content: space-between; color: var(--secondary); font-size: 12px }
.selection-count { color: var(--accent); font-size: 11px }
.document-options { display: grid; gap: 6px; max-height: 310px; overflow-y: auto; scrollbar-width: thin }
.document-option { display: flex; align-items: center; gap: 9px; padding: 11px 12px; border: 1px solid var(--line); border-radius: 7px; background: var(--inset); cursor: pointer; line-height: 1.5 }
.document-option:hover { border-color: var(--control-line) }
.document-option.is-selected { background: var(--selection); border-color: var(--control-line) }
.document-option.is-unavailable { opacity: .6; cursor: not-allowed }
.document-option svg { color: var(--secondary); flex: none }
.document-name { flex: 1; min-width: 0; overflow-wrap: anywhere; color: var(--text); font-size: 12px }
.document-option small { font-size: 10px }
.document-option input, .chapter-choice input { width: 15px; height: 15px; margin: 0; accent-color: var(--accent); flex: none }
.picker-empty { font-size: 12px }
.chapters { min-width: 0; margin: 0; padding: 0 14px; border: 1px solid var(--line); border-radius: 8px; background: var(--inset) }
.chapters > summary { display: flex; align-items: center; gap: 8px; cursor: pointer; list-style: none; padding: 12px 0; color: var(--text); font-size: 12px; font-weight: 600 }
.chapters > summary::-webkit-details-marker { display: none }
.chapters > summary span { margin-left: auto; color: var(--secondary); font-weight: 400 }
.chapters[open] > summary { border-bottom: 1px solid var(--line) }
.chapters > summary:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px }
.chapter-row { padding: 12px 0; border-bottom: 1px solid var(--line) }
.chapter-row:last-child { border-bottom: 0 }
.chapter-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px }
.chapter-choice { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--text); cursor: pointer; flex-wrap: wrap }
.required-label { display: inline-flex; gap: 3px; align-items: center; font-size: 10px; color: var(--secondary) }
.chapter-order { display: flex; flex: none; color: var(--secondary) }
.chapter-order button { border: 1px solid var(--line); background: var(--surface); padding: 5px }
.chapter-order button + button { margin-left: 4px }
.chapter-order button:disabled { opacity: .35; cursor: not-allowed }
.chapter-sequence { border: 1px solid var(--line); border-radius: 8px; padding: 14px; background: var(--inset); min-width: 0 }
.sequence-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap }
.sequence-heading h4 { margin: 0; color: var(--text); font-size: 12px }
.sequence-heading > span, .sequence-hint { color: var(--secondary); font-size: 11px }
.sequence-hint { margin: 8px 0 12px; line-height: 1.6 }
.sequence-list { list-style: none; padding: 0; margin: 0; display: grid; gap: 8px }
.sequence-item { display: flex; align-items: center; gap: 10px; padding: 10px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface) }
.sequence-feedback { position: sticky; top: 0; z-index: 1; min-height: 22px; margin: 0 0 8px; padding: 3px 0; font-size: 12px; line-height: 1.6; color: var(--accent); background: var(--inset); overflow-wrap: anywhere }
.sequence-item.is-moved { border-color: var(--accent); background: var(--selection); box-shadow: inset 3px 0 var(--accent) }
.section-reorder-move { transition: transform var(--motion-panel) var(--ease-out) }
.sequence-position { display: grid; place-items: center; flex: none; width: 22px; height: 22px; border-radius: 50%; background: var(--selection); color: var(--accent); font-size: 11px }
.sequence-title { flex: 1; min-width: 0; overflow-wrap: anywhere; font-size: 12px; color: var(--text) }
.sequence-item .chapter-order button { font-size: 11px; gap: 4px; padding: 6px }
@media (max-width: 420px) { .sequence-item { flex-wrap: wrap } .sequence-item .chapter-order { margin-left: auto } }
.chapter-instruction { margin: 9px 0 0 23px }
.chapter-instruction textarea { width: 100%; min-width: 0; min-height: 72px; font-size: 12px; line-height: 1.6; padding: 9px 10px }
.chapter-default { margin: 0 0 5px; font-size: 12px; line-height: 1.7; color: var(--secondary) }
.chapter-default span { display: block; font-size: 11px; color: var(--secondary) }
.chapter-hint { margin: 0; font-size: 11px; line-height: 1.6; color: var(--secondary) }
.chapter-row.is-disabled .chapter-choice { color: var(--secondary) }
.create-task { justify-content: center }
.report-workspace :deep(button:focus-visible), .report-workspace :deep(input[type="checkbox"]:focus-visible) { outline: 2px solid var(--accent); outline-offset: 3px }
.task-history-item { display: flex; align-items: center; gap: var(--space-2); border: 1px solid var(--line); border-radius: var(--radius-control) }
.task-history-item.is-active { background: var(--selection); border-color: var(--control-line) }
.task-history-main { min-width: 0; flex: 1; text-align: start; background: transparent; border: 0; padding: 0 }
.task-history-item .icon-button { color: var(--accent) }
.task-history-main strong { overflow-wrap: anywhere }
.task-history-main p { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; overflow-wrap: anywhere; font-size: 11px }
.report-output { margin-top: 0 }
.output-heading { margin: 0; padding-bottom: 18px; border-bottom: 1px solid var(--line); gap: 14px; align-items: flex-start }
.output-heading > div:first-child { min-width: 0; overflow-wrap: anywhere }
.output-heading h2 { font-size: var(--font-heading); line-height: 1.6 }
.output-heading p { margin: 5px 0 0; font-size: 12px }
@media (max-width: 600px) {
  .task-builder, .history-panel, .report-output { padding: 12px }
  .creation-steps { gap: 4px; padding-bottom: 12px }
  .creation-steps button { gap: 4px; font-size: 11px }
  .output-heading { padding-bottom: 10px; gap: 8px }
  .output-heading h2 { font-size: var(--font-body) }
  .segmented-control button { padding-inline: var(--space-2) }
}
</style>
