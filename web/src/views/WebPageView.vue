<script lang="ts" setup>
import {ref} from 'vue';
import {ChevronDown, Database, Download, Globe, Loader2} from 'lucide-vue-next';
import {api} from '../api';
import type {PageResponse} from '../types';

interface ImportResult {
  chunkCount: number;
  md5: string;
  duplicated: boolean;
  message: string;
}

const url = ref(''), loading = ref(false), error = ref(''), result = ref<PageResponse | null>(null),
    previewCollapsed = ref(false), importing = ref(false), importError = ref(''),
    importResult = ref<ImportResult | null>(null);
const engine = ref<'auto' | 'jsoup' | 'firecrawl'>('auto');
const togglePreview = () => {
  previewCollapsed.value = !previewCollapsed.value
};

async function fetchPage() {
  if (!url.value.trim()) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  previewCollapsed.value = false;
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
  if (!result.value) return;
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
  <section class="page-intro">
    <div>
      <p class="eyebrow">INGESTION / WEB</p>
      <h2>获取外部网页</h2>
      <p>输入网页 URL，抓取正文转为 Markdown，支持本地直连与 Firecrawl 云端增强抓取，可直接导入知识库或下载为 TXT。</p>
    </div>
  </section>

  <div class="panel" style="padding:24px;max-width:860px">
    <div class="field">
      <label>网页 URL</label>
      <div style="display:flex;gap:10px">
        <input v-model="url" type="url" placeholder="https://example.com/article" @keyup.enter="fetchPage"/>
        <select v-model="engine" aria-label="抓取引擎" style="flex:none;width:150px">
          <option value="auto">自动（推荐）</option>
          <option value="jsoup">普通抓取</option>
          <option value="firecrawl">增强抓取</option>
        </select>
        <button :disabled="loading||!url.trim()" class="button primary" @click="fetchPage">
          <Loader2 v-if="loading" :size="15" class="spin"/>
          <Globe v-else :size="15"/>
          {{ loading ? '抓取中…' : '抓取网页' }}
        </button>
      </div>
      <p class="muted" style="font-size:12px;line-height:1.6;margin:8px 2px 0">
        <strong>普通抓取</strong>：本地直连静态 HTML，自动识别 UTF-8 / GBK 编码，零成本；
        <strong>增强抓取</strong>：Firecrawl 云端渲染，支持 JS 动态页面与反爬站点（按页计费，需在后端配置 API Key）；
        <strong>自动</strong>：先普通抓取，正文为空或失败时自动回退增强抓取。
      </p>
    </div>

    <div v-if="error" class="notice error" style="margin-top:16px">{{ error }}</div>

    <div v-if="result" style="margin-top:20px">
      <div class="section-heading">
        <div>
          <h3>{{ result.title || '无标题' }}</h3>
          <p class="muted">{{ result.url }}</p>
        </div>
        <div class="actions">
          <button class="button" @click="download">
            <Download :size="15"/>
            下载 TXT
          </button>
          <button :disabled="importing" class="button primary" @click="importToKB">
            <Loader2 v-if="importing" :size="15" class="spin"/>
            <Database v-else :size="15"/>
            {{ importing ? '导入中…' : '导入知识库' }}
          </button>
        </div>
      </div>

      <div style="display:flex;gap:18px;flex-wrap:wrap;font-size:13px;color:#71837c;margin-bottom:6px">
        <span>文件：<strong class="muted" style="color:#3d554d">{{ result.fileName }}</strong></span>
        <span>字符数：{{ result.length }}</span>
        <span>抓取时间：{{ result.fetchedAt }}</span>
      </div>

      <div v-if="importError" class="notice error" style="margin-top:12px">
        <strong>导入失败：</strong>{{ importError }}
      </div>
      <div v-if="importResult" class="notice" style="margin-top:12px">
        <strong>导入成功</strong>
        <span v-if="importResult.duplicated">（文件已存在，跳过重复导入）</span>
        <span style="margin-left:10px">切片数：{{ importResult.chunkCount }} · MD5：{{ importResult.md5 }}</span>
        <div v-if="importResult.message" style="margin-top:4px">{{ importResult.message }}</div>
      </div>

      <div class="section-heading collapsible-heading" @click="togglePreview">
        <span class="heading-group"><h3>Markdown 预览</h3>
          <ChevronDown :size="16" :class="['chevron', {collapsed: previewCollapsed}]"/>
        </span>
      </div>
      <pre v-show="!previewCollapsed" class="code-view" style="max-height:520px">{{ result.markdown }}</pre>
    </div>
  </div>
</template>

<style scoped>
.collapsible-heading {
  cursor: pointer;
  user-select: none;
}
.heading-group {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.collapsible-heading .chevron {
  transition: transform .15s ease;
}
.collapsible-heading .chevron.collapsed {
  transform: rotate(-90deg);
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
