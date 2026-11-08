<script lang="ts" setup>
import {ref} from 'vue';
import {CheckCircle2, FileUp} from 'lucide-vue-next';
import {api} from '../api';

const file = ref<File | null>(null), kind = ref(''), loading = ref(false), error = ref(''), success = ref('');

function choose(e: Event) {
  file.value = (e.target as HTMLInputElement).files?.[0] || null
}

async function upload() {
  if (!file.value) return;
  loading.value = true;
  error.value = '';
  success.value = '';
  try {
    const x = await api.upload(file.value, kind.value);
    success.value = JSON.stringify(x)
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}
</script>
<template>
  <section class="page-intro">
    <div><p class="eyebrow">INGESTION / SOURCE</p>
      <h2>导入文档</h2>
      <p>将本地资料加入知识库，导入后即可检索和用于报告生成。</p></div>
  </section>
  <div class="panel" style="padding:24px;max-width:760px">
   <div class="file-drop">
  <FileUp :size="30"/>
  <strong>{{ file?.name || '选择一个文档文件' }}</strong><br>
  <span>支持 TXT、DOCX、PDF、XLSX、XLS</span><br>
  <label class="button" style="display: inline-block; margin-top: 18px;"><input accept=".txt,.docx,.pdf,.xlsx,.xls" type="file" @change="choose"/>选择文件</label>
</div>
    <div class="form-grid" style="margin-top:18px">
      <div class="field"><label>文件类型接口</label><select v-model="kind">
        <option value="">TXT</option>
        <option value="word">Word / DOCX</option>
        <option value="pdf">PDF</option>
        <option value="excel">Excel</option>
      </select></div>
      <div style="display:flex;align-items:end">
        <button :disabled="!file||loading" class="button primary" @click="upload">
          <FileUp :size="15"/>
          {{ loading ? '导入中…' : '开始导入' }}
        </button>
      </div>
    </div>
    <div v-if="error" class="notice error" style="margin-top:16px">{{ error }}</div>
    <div v-if="success" class="notice" style="margin-top:16px">
      <CheckCircle2 :size="15" style="vertical-align:-3px"/>
      导入请求已完成：{{ success }}
    </div>
  </div>
</template>
