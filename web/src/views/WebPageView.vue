<script lang="ts" setup>
import {ref} from 'vue';
import {ChevronRight, Database, Download, Globe, Loader2} from 'lucide-vue-next';
import {api} from '../api';
import type {PageResponse} from '../types';

interface ImportResult {
  chunkCount: number;
  md5: string;
  duplicated: boolean;
  message: string;
}

const url = ref(''), loading = ref(false), error = ref(''), result = ref<PageResponse | null>(null),
    importing = ref(false), importError = ref(''),
    importResult = ref<ImportResult | null>(null);
const engine = ref<'auto' | 'jsoup' | 'firecrawl'>('auto');
async function fetchPage() {
  if (loading.value || importing.value || !url.value.trim()) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  importResult.value = null;
  importError.value = '';
  try {
    result.value = await api.fetchPage(url.value.trim(), engine.value)
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

function download() {
  if (!result.value) return;
  const blob = new Blob([result.value.markdown], {type: 'text/plain;charset=utf-8'});
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = result.value.fileName || 'page.txt';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(link.href);
}

async function importToKB() {
  if (!result.value || importing.value) return;
  importing.value = true;
  importError.value = '';
  importResult.value = null;
  try {
    const file = new File([result.value.markdown], result.value.fileName || 'page.txt', {type: 'text/plain'});
    importResult.value = await api.upload(file, '') as ImportResult;
  } catch (e) {
    importError.value = String(e)
  } finally {
    importing.value = false
  }
}
</script>
<template>
  <div class="page-intro"><p>将网页正文转为 Markdown，可下载为 TXT 或导入知识库。</p></div>

  <div class="flow narrow-panel">
    <form class="panel panel-pad" @submit.prevent="fetchPage">
      <div class="fetch-fields">
        <div class="field">
          <label for="webpage-url">网页 URL</label>
          <input id="webpage-url" v-model="url" type="url" placeholder="https://example.com/article"/>
        </div>
        <div class="field">
          <label for="webpage-engine">抓取引擎</label>
          <select id="webpage-engine" v-model="engine">
            <option value="auto">自动（推荐）</option>
            <option value="jsoup">普通抓取</option>
            <option value="firecrawl">增强抓取</option>
          </select>
        </div>
        <div class="form-action">
          <button :disabled="loading || importing || !url.trim()" class="button primary" type="submit">
            <Loader2 v-if="loading" :size="15" class="spin" aria-hidden="true"/>
            <Globe v-else :size="15" aria-hidden="true"/>
            {{ loading ? '抓取中…' : '抓取网页' }}
          </button>
        </div>
      </div>
      <p class="muted fetch-help">普通抓取直接读取静态网页。增强抓取用 Firecrawl 处理动态网页，按页计费，需在后端配置 API Key。自动模式会在普通抓取失败或正文为空时改用增强抓取。</p>
    </form>

    <div v-if="error" class="notice error" role="alert">{{ error }}</div>

    <section v-if="result" class="panel panel-pad flow webpage-result" aria-labelledby="webpage-result-title">
      <header class="section-heading">
        <div>
          <h2 id="webpage-result-title">{{ result.title || '无标题' }}</h2>
          <p class="muted">{{ result.url }}</p>
        </div>
        <div class="actions">
          <button class="button" type="button" @click="download"><Download :size="15" aria-hidden="true"/>下载 TXT</button>
          <button :disabled="importing" class="button primary" type="button" @click="importToKB">
            <Loader2 v-if="importing" :size="15" class="spin" aria-hidden="true"/>
            <Database v-else :size="15" aria-hidden="true"/>
            {{ importing ? '导入中…' : '导入知识库' }}
          </button>
        </div>
      </header>

      <dl class="fetch-meta">
        <div><dt>文件</dt><dd>{{ result.fileName }}</dd></div>
        <div><dt>字符数</dt><dd>{{ result.length }}</dd></div>
        <div><dt>抓取时间</dt><dd>{{ result.fetchedAt }}</dd></div>
      </dl>

      <div v-if="importError" class="notice error" role="alert"><strong>导入失败</strong> {{ importError }}</div>
      <div v-if="importResult" class="notice" role="status">
        <strong>{{ importResult.duplicated ? '文件已在知识库中' : '导入成功' }}</strong>
        <p>{{ importResult.chunkCount }} 个切片 · MD5 {{ importResult.md5 }}</p>
        <p v-if="importResult.message">{{ importResult.message }}</p>
      </div>

      <details class="webpage-preview" open>
        <summary><ChevronRight :size="16" class="disclosure-chevron" aria-hidden="true"/>Markdown 预览</summary>
        <pre class="code-view" tabindex="0" aria-label="Markdown 内容">{{ result.markdown }}</pre>
      </details>
    </section>
  </div>
</template>

<style scoped>
.fetch-fields { display: grid; grid-template-columns: minmax(0, 1fr) minmax(128px, auto) auto; gap: var(--space-3) }
.fetch-fields .button { min-height: 40px }
.fetch-help { font-size: var(--font-small); line-height: 1.65; margin: var(--space-3) 0 0 }
.webpage-result .section-heading { margin: 0 }
.webpage-result .section-heading > div:first-child { min-width: 0 }
.webpage-result p { margin: var(--space-1) 0 0; font-size: var(--font-small) }
.fetch-meta { display: flex; flex-wrap: wrap; gap: var(--space-3) var(--space-5); margin: 0; padding-block: var(--space-3); border-block: 1px solid var(--line) }
.fetch-meta div { min-width: 0 }
.fetch-meta dt { color: var(--secondary); font-size: .75rem }
.fetch-meta dd { margin: var(--space-1) 0 0; overflow-wrap: anywhere; font-size: var(--font-small) }
.webpage-preview summary { display: flex; align-items: center; gap: var(--space-2); padding-block: var(--space-2); font-weight: 600 }
.webpage-preview summary::-webkit-details-marker { display: none }
.webpage-preview .code-view { max-height: 520px }
@media (max-width: 600px) {
  .fetch-fields { grid-template-columns: minmax(0, 1fr) auto }
  .fetch-fields > .field:first-child { grid-column: 1 / -1 }
}
</style>
