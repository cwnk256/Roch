<script setup lang="ts">
import type {ExportOptions} from '../lib/report'
import {useId} from 'vue'
const options = defineModel<ExportOptions>({required: true})
const selectId = useId()
</script>
<template>
  <fieldset class="report-export">
    <legend>PDF 导出设置</legend>
    <div class="field">
      <label :for="selectId">引用格式</label>
      <select :id="selectId" v-model="options.citationStyle">
        <option value="NUMBERED">顺序编号</option>
        <option value="FILE_LOCATION">文件名与位置</option>
      </select>
    </div>
    <div class="export-inclusions">
      <span class="export-label">包含内容</span>
      <div class="export-checks">
        <label><input v-model="options.includeToc" type="checkbox"> 目录</label>
        <label><input v-model="options.includeEvidenceAppendix" type="checkbox"> 证据附录</label>
      </div>
    </div>
  </fieldset>
</template>
<style scoped>
.report-export { min-width: 0; display: grid; grid-template-columns: minmax(0,1fr); gap: 16px; margin: 0; padding: 16px; background: var(--inset); border: 1px solid var(--line); border-radius: 8px }
legend { padding: 0 6px; font-size: 12px; font-weight: 600; color: var(--text) }
.export-label { font-size: 12px; color: var(--secondary) }
.export-inclusions { display: grid; align-content: start; gap: 12px }
.export-checks { display: flex; gap: 18px; flex-wrap: wrap; min-height: 22px }
.export-checks label { display: inline-flex; align-items: center; gap: 7px; font-size: 13px; color: var(--text); cursor: pointer }
input { accent-color: var(--accent); width: 15px; height: 15px; margin: 0 }
select { width: 100%; min-width: 0 }
</style>
